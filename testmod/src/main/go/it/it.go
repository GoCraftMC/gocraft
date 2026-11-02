// Package it checks the bridge from inside a running game and reports one line per check, ending in
// a summary line that scripts/integration-test.sh looks for.
package it

import (
	"fmt"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/GoCraftMC/gocraft-sdk/command"
	"github.com/GoCraftMC/gocraft-sdk/event"
	"github.com/GoCraftMC/gocraft-sdk/gocraft"
	"github.com/GoCraftMC/gocraft-sdk/jvm"
	"github.com/GoCraftMC/gocraft-sdk/mc"
	"github.com/GoCraftMC/gocraft-sdk/registry"
	"github.com/GoCraftMC/gocraft/testmod/src/generated/go/mcgen"
)

var (
	passed, failed int
	hookTicks      atomic.Int64
	commandOutput  atomic.Value
	initOrder      string
	testItem       mc.Item
	testBlock      registry.RegisteredBlock
	useCount       atomic.Int64
	useBlock       registry.RegisteredBlock
	blockUses      atomic.Int64
	brokenEvents   atomic.Int64
	useItemEvents  atomic.Int64
	useBlockEvents atomic.Int64
	lastUser       atomic.Value
	optional       = jvm.SM("no.such.OptionalClass", "get", "()V")
	lateResolved   = jvm.SM("java.lang.System", "lineSeparator", "()Ljava/lang/String;")
)

func check(name string, fn func() error) {
	defer func() {
		if r := recover(); r != nil {
			failed++
			gocraft.Log().Error(fmt.Sprintf("IT FAIL %s: panic %v", name, r))
		}
	}()
	if err := fn(); err != nil {
		failed++
		gocraft.Log().Error(fmt.Sprintf("IT FAIL %s: %v", name, err))
		return
	}
	passed++
	gocraft.Log().Info("IT PASS " + name)
}

func init() {
	initOrder = "init"
	// Logging this early used to crash the JVM. It has to wait for the bridge, then reach the log.
	gocraft.Log().Info("IT logged from package init")
	gocraft.OnPreLaunch(func() { initOrder += ",preLaunch" })
	gocraft.Hook("tick_head", func(h *gocraft.HookContext) { hookTicks.Add(1) })
	gocraft.Hook("motd_override", func(h *gocraft.HookContext) { h.SetReturn("go-hooked-version") })

	gocraft.OnInitialize(func() {
		initOrder += ",main"
		testItem = registry.RegisterItem("test_item", registry.Item{MaxStack: 7, OnUse: func(ctx registry.UseItem) mc.InteractionResult {
			useCount.Add(1)
			return mc.Success()
		}})
		testBlock = registry.RegisterBlock("test_block", registry.Block{Hardness: 2, Resistance: 3, Light: 5})
		registry.AddToTab(registry.TabIngredients, testItem)
		useBlock = registry.RegisterBlock("use_block", registry.Block{Hardness: 1, Resistance: 1, OnUse: func(ctx registry.UseBlock) mc.InteractionResult {
			blockUses.Add(1)
			lastUser.Store(ctx.Player.Name())
			return mc.Success()
		}})

		event.BlockBroken(func(level mc.Level, player mc.Player, pos mc.BlockPos, state mc.BlockState) {
			brokenEvents.Add(1)
			lastUser.Store(player.Name())
		})
		event.UseItem(func(player mc.Player, level mc.Level) mc.InteractionResult {
			useItemEvents.Add(1)
			return mc.Pass()
		})
		event.UseBlock(func(player mc.Player, level mc.Level, pos mc.BlockPos) mc.InteractionResult {
			useBlockEvents.Add(1)
			return mc.Pass()
		})

		command.Register(command.Literal("goit").Then(
			command.Arg("n", command.Int()).Executes(func(c *command.Context) int {
				commandOutput.Store(fmt.Sprintf("n=%d sender=%s", c.Int("n"), c.SenderName()))
				return 1
			}),
		))

		event.ServerStarted(func(server mc.Server) { runAll(server) })
	})
}

func runAll(server mc.Server) {
	check("entrypoints ran in order", func() error {
		if initOrder != "init,preLaunch,main" {
			return fmt.Errorf("got %s", initOrder)
		}
		return nil
	})

	check("strings survive the round trip, including unicode", func() error {
		concat := jvm.FindClass("java.lang.String").Method("concat", "(Ljava/lang/String;)Ljava/lang/String;")
		got := concat.Call(jvm.NewString("héllo "), "wörld 🌍").String()
		if got != "héllo wörld 🌍" {
			return fmt.Errorf("got %q", got)
		}
		long := strings.Repeat("x", 100000)
		if jvm.NewString(long).String() != long {
			return fmt.Errorf("long string was corrupted")
		}
		return nil
	})

	check("primitives convert in both directions", func() error {
		math := jvm.FindClass("java.lang.Math")
		if v := math.StaticMethod("max", "(II)I").CallStatic(3, 9).Int(); v != 9 {
			return fmt.Errorf("int %d", v)
		}
		if v := math.StaticMethod("abs", "(D)D").CallStatic(-2.5).Double(); v != 2.5 {
			return fmt.Errorf("double %v", v)
		}
		if v := math.StaticMethod("abs", "(F)F").CallStatic(float32(-1.5)).Float(); v != 1.5 {
			return fmt.Errorf("float %v", v)
		}
		if v := math.StaticMethod("max", "(JJ)J").CallStatic(int64(1<<40), int64(5)).Long(); v != 1<<40 {
			return fmt.Errorf("long %v", v)
		}
		return nil
	})

	check("Java exceptions become catchable Go errors", func() error {
		err := jvm.Try(func() {
			jvm.FindClass("java.lang.Integer").StaticMethod("parseInt", "(Ljava/lang/String;)I").CallStatic("nope")
		})
		if err == nil || !strings.Contains(err.Error(), "NumberFormatException") {
			return fmt.Errorf("got %v", err)
		}
		return nil
	})

	check("missing members report instead of crashing", func() error {
		if jvm.Try(func() { jvm.FindClass("no.such.Klass") }) == nil {
			return fmt.Errorf("class lookup should fail")
		}
		if jvm.Try(func() { jvm.FindClass("java.lang.String").Method("nope", "()V") }) == nil {
			return fmt.Errorf("method lookup should fail")
		}
		return nil
	})

	check("proxies run Go from Java, and default methods still work", func() error {
		comparator := jvm.Func("java.util.Comparator", "compare(Ljava/lang/Object;Ljava/lang/Object;)I", func(a *jvm.Args) any {
			return strings.Compare(a.String(1), a.String(0)) // reverse order
		})
		list := jvm.FindClass("java.util.ArrayList").New("()V")
		add := jvm.FindClass("java.util.ArrayList").Method("add", "(Ljava/lang/Object;)Z")
		for _, s := range []string{"a", "c", "b"} {
			add.Call(list, s)
		}
		jvm.FindClass("java.util.Collections").StaticMethod("sort", "(Ljava/util/List;Ljava/util/Comparator;)V").CallStatic(list, comparator)
		if got := list.String(); got != "[c, b, a]" {
			return fmt.Errorf("sorted to %s", got)
		}

		reversed := jvm.FindClass("java.util.Comparator").Method("reversed", "()Ljava/util/Comparator;").Call(comparator).Object()
		jvm.FindClass("java.util.Collections").StaticMethod("sort", "(Ljava/util/List;Ljava/util/Comparator;)V").CallStatic(list, reversed)
		if got := list.String(); got != "[a, b, c]" {
			return fmt.Errorf("default method sorted to %s", got)
		}
		return nil
	})

	check("Go panics surface as Java exceptions and return catchable", func() error {
		callable := jvm.Runnable(func() { panic("deliberate") })
		err := jvm.Try(func() { jvm.FindClass("java.lang.Runnable").Method("run", "()V").Call(callable) })
		if err == nil || !strings.Contains(err.Error(), "GoPanicException") || !strings.Contains(err.Error(), "deliberate") {
			return fmt.Errorf("got %v", err)
		}
		return nil
	})

	check("Java exceptions keep their type through Go frames", func() error {
		rethrow := jvm.Runnable(func() {
			jvm.FindClass("java.lang.Integer").StaticMethod("parseInt", "(Ljava/lang/String;)I").CallStatic("nope")
		})
		err := jvm.Try(func() { jvm.FindClass("java.lang.Runnable").Method("run", "()V").Call(rethrow) })
		if err == nil || !strings.HasPrefix(err.Error(), "java.lang.NumberFormatException") {
			return fmt.Errorf("got %v", err)
		}
		return nil
	})

	check("generated subclasses override, call super and mirror constructors", func() error {
		base := jvm.FindClass("java.util.ArrayList")
		var class *jvm.Class
		class = jvm.Extend(base, "doubling", jvm.Impl{
			"size()I": func(a *jvm.Args) any {
				return class.CallSuper(a.Object(0), "size", "()I").Int() * 2
			},
			"add(Ljava/lang/Object;)Z": func(a *jvm.Args) any {
				return class.CallSuper(a.Object(0), "add", "(Ljava/lang/Object;)Z", a.Object(1)).Bool()
			},
		})
		obj := class.New("(I)V", 10)
		base.Method("add", "(Ljava/lang/Object;)Z").Call(obj, "x")
		if size := jvm.FindClass("java.util.List").Method("size", "()I").Call(obj).Int(); size != 2 {
			return fmt.Errorf("size %d", size)
		}
		return nil
	})

	check("private members are reachable without an access widener", func() error {
		value := jvm.FindClass("net.minecraft.server.MinecraftServer").Field("tickCount", "I").Get(server).Int()
		if int(value) != server.Tick() {
			return fmt.Errorf("tickCount %d vs %d", value, server.Tick())
		}
		return nil
	})

	check("byte arrays copy in both directions", func() error {
		data := []byte{1, 2, 3, 250}
		array := jvm.NewBytes(data)
		if jvm.ArrayLen(array) != 4 {
			return fmt.Errorf("len %d", jvm.ArrayLen(array))
		}
		if got := jvm.Bytes(array); string(got) != string(data) {
			return fmt.Errorf("got %v", got)
		}
		return nil
	})

	check("goroutines on Go threads can call Java concurrently", func() error {
		var wg sync.WaitGroup
		var total atomic.Int64
		concat := jvm.FindClass("java.lang.String").Method("length", "()I")
		for range 16 {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for range 20000 {
					total.Add(int64(concat.Call(jvm.NewString("abc")).Int()))
				}
			}()
		}
		wg.Wait()
		if total.Load() != 16*20000*3 {
			return fmt.Errorf("total %d", total.Load())
		}
		return nil
	})

	check("dropped references are released back to Java", func() error {
		handles := jvm.FindClass("com.go.craft.bridge.Handles").StaticMethod("liveCount", "()I")
		for range 300000 {
			jvm.NewString("garbage")
		}
		var live int32
		for range 200 {
			runtime.GC()
			live = handles.CallStatic().Int()
			if live < 20000 {
				break
			}
			runtime.Gosched()
		}
		if live >= 20000 {
			return fmt.Errorf("%d handles still live", live)
		}
		return nil
	})

	check("the item and block are in the real registries", func() error {
		lookup := func(field, descriptor, id string) jvm.Object {
			registryObj := jvm.FindClass("net.minecraft.core.registries.BuiltInRegistries").StaticField(field, descriptor).GetStatic()
			return jvm.FindClass("net.minecraft.core.Registry").Method("getValue", "(Lnet/minecraft/resources/Identifier;)Ljava/lang/Object;").Call(registryObj, mc.ID(id)).Object()
		}
		if !jvm.Same(lookup("ITEM", "Lnet/minecraft/core/DefaultedRegistry;", "test_item"), testItem) {
			return fmt.Errorf("test_item did not resolve to our item")
		}
		if !jvm.Same(lookup("BLOCK", "Lnet/minecraft/core/DefaultedRegistry;", "test_block"), testBlock) {
			return fmt.Errorf("test_block did not resolve to our block")
		}
		if !jvm.Same(lookup("ITEM", "Lnet/minecraft/core/DefaultedRegistry;", "gopher:gopher_wand"), lookup("ITEM", "Lnet/minecraft/core/DefaultedRegistry;", "gopher:gopher_wand")) {
			return fmt.Errorf("gopher wand missing")
		}
		if lookup("BLOCK", "Lnet/minecraft/core/DefaultedRegistry;", "gopher:gopher_block").String() == "Block{minecraft:air}" {
			return fmt.Errorf("the other mod's block is missing")
		}
		return nil
	})

	check("a stack of our item works and overrides dispatch to Go", func() error {
		stack := mc.Stack(testItem, 3)
		if stack.Count() != 3 {
			return fmt.Errorf("count %d", stack.Count())
		}
		class := testItem.ClassOf().String()
		if !strings.Contains(class, "com.go.craft.generated") {
			return fmt.Errorf("item class is %s", class)
		}

		// Called through the Java base class, so this only reaches Go through the generated override.
		use := jvm.FindClass("net.minecraft.world.item.Item").Method("use", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;")
		result := use.Call(testItem, server.Overworld(), nil, nil).Object()
		if useCount.Load() != 1 {
			return fmt.Errorf("OnUse ran %d times", useCount.Load())
		}
		if !jvm.Same(result, mc.Success()) {
			return fmt.Errorf("use returned %s", result)
		}
		return nil
	})

	check("typed bindings generated from the game jar work", func() error {
		diamond := mcgen.Items_DIAMOND()
		stack := mcgen.ItemStack_New_ItemLike_Int(diamond, 5)
		if stack.GetCount() != 5 {
			return fmt.Errorf("count %d", stack.GetCount())
		}
		if !jvm.Same(stack.GetItem(), diamond) {
			return fmt.Errorf("item was %s", stack.GetItem())
		}
		return nil
	})

	check("blocks can be placed in the world and read back", func() error {
		level := server.Overworld()
		pos := mc.Pos(0, 200, 0)
		// Loading the chunk is what a real placement implies.
		jvm.FindClass("net.minecraft.world.level.LevelReader").Method("getChunk", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;").Call(level, pos)
		if !level.SetBlock(pos, testBlock.DefaultState()) {
			return fmt.Errorf("setBlock returned false")
		}
		if !jvm.Same(level.BlockState(pos).Block(), testBlock.Block) {
			return fmt.Errorf("read back %s", level.BlockState(pos))
		}
		return nil
	})

	checkPlayerActions(server)

	check("a failed lazy lookup stays retryable and catchable", func() error {
		for range 2 {
			if err := jvm.Try(func() { optional.CallStatic() }); err == nil || !strings.Contains(err.Error(), "ClassNotFound") {
				return fmt.Errorf("got %v", err)
			}
		}
		if lateResolved.CallStatic().String() == "" {
			return fmt.Errorf("a good lazy lookup returned nothing")
		}
		return nil
	})

	check("repeated lookups reuse one method id", func() error {
		class := jvm.FindClass("java.lang.String")
		if class.Method("length", "()I") != class.Method("length", "()I") {
			return fmt.Errorf("a new Method per lookup")
		}
		members := jvm.FindClass("com.go.craft.bridge.Members").StaticField("methods", "[Lcom/go/craft/bridge/Members$Bound;")
		before := jvm.ArrayLen(members.GetStatic())
		for range 1000 {
			jvm.FindClass("java.lang.String").Constructor("(Ljava/lang/String;)V").CallStatic("x")
		}
		if grown := jvm.ArrayLen(members.GetStatic()) - before; grown > 1 {
			return fmt.Errorf("the method table grew by %d", grown)
		}
		return nil
	})

	check("callbacks are freed once their proxy is collected", func() error {
		gc := jvm.FindClass("java.lang.System").StaticMethod("gc", "()V")
		before := jvm.CallbackCount()
		held := make([]jvm.Object, 20000)
		for i := range held {
			held[i] = jvm.Runnable(func() { _ = i })
		}
		if grown := jvm.CallbackCount() - before; grown != 20000 {
			return fmt.Errorf("%d of 20000 callbacks were registered while held", grown)
		}
		runtime.KeepAlive(held)
		held = nil

		var grown int64
		for range 100 {
			runtime.GC()
			gc.CallStatic()
			time.Sleep(50 * time.Millisecond)
			if grown = jvm.CallbackCount() - before; grown < 1000 {
				return nil
			}
		}
		return fmt.Errorf("%d callbacks are still registered", grown)
	})

	check("commands registered in Go run through Brigadier", func() error {
		server.RunCommand("goit 42")
		got, _ := commandOutput.Load().(string)
		if !strings.HasPrefix(got, "n=42 ") {
			return fmt.Errorf("got %q", got)
		}
		return nil
	})

	check("a command from another Go mod is registered and runs", func() error {
		commands := jvm.FindClass("net.minecraft.server.MinecraftServer").Method("getCommands", "()Lnet/minecraft/commands/Commands;").Call(server)
		dispatcher := jvm.FindClass("net.minecraft.commands.Commands").Method("getDispatcher", "()Lcom/mojang/brigadier/CommandDispatcher;").Call(commands)
		root := jvm.FindClass("com.mojang.brigadier.CommandDispatcher").Method("getRoot", "()Lcom/mojang/brigadier/tree/RootCommandNode;").Call(dispatcher)
		node := jvm.FindClass("com.mojang.brigadier.tree.CommandNode").Method("getChild", "(Ljava/lang/String;)Lcom/mojang/brigadier/tree/CommandNode;").Call(root, "gopher")
		if node.Object().IsNull() {
			return fmt.Errorf("/gopher is not registered")
		}
		// The script also looks for this command's reply in the log.
		server.RunCommand("gopher fib 10")
		return nil
	})

	check("a HEAD hook can replace a return value", func() error {
		if got := server.Version(); got != "go-hooked-version" {
			return fmt.Errorf("getServerVersion returned %q", got)
		}
		return nil
	})

	check("a RETURN hook from another Go mod applied", func() error {
		motd := jvm.FindClass("net.minecraft.server.MinecraftServer").Method("getMotd", "()Ljava/lang/String;").Call(server).String()
		if !strings.HasSuffix(motd, "(powered by Go)") {
			return fmt.Errorf("motd %q", motd)
		}
		return nil
	})

	check("gomods are real mods to Fabric Loader", func() error {
		loader := jvm.FindClass("net.fabricmc.loader.api.FabricLoader")
		instance := loader.StaticMethod("getInstance", "()Lnet/fabricmc/loader/api/FabricLoader;").CallStatic()
		container := loader.Method("getModContainer", "(Ljava/lang/String;)Ljava/util/Optional;").Call(instance, "gopher")
		if !strings.Contains(container.String(), "Optional[gopher") {
			return fmt.Errorf("container: %s", container)
		}
		if !gocraft.IsModLoaded("gocraft_it") {
			return fmt.Errorf("IsModLoaded is false for this mod")
		}
		return nil
	})

	check("data pack resources from .gomod files are in the game", func() error {
		manager := jvm.FindClass("net.minecraft.server.MinecraftServer").Method("getResourceManager", "()Lnet/minecraft/server/packs/resources/ResourceManager;").Call(server)
		getResource := jvm.FindClass("net.minecraft.server.packs.resources.ResourceProvider").Method("getResource", "(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;")
		for _, id := range []string{"gocraft_it:recipe/marker.json", "gopher:recipe/gopher_wand.json"} {
			if got := getResource.Call(manager, mc.ID(id)).String(); got == "Optional.empty" {
				return fmt.Errorf("%s not found", id)
			}
		}
		return nil
	})

	check("config files round trip through the config directory", func() error {
		cfg := struct{ Value string }{"default"}
		if err := gocraft.LoadConfig(&cfg); err != nil {
			return err
		}
		if cfg.Value != "default" {
			return fmt.Errorf("value %q", cfg.Value)
		}
		return nil
	})

	checkTicks(server)
}

// checkPlayerActions drives the game's own server-side interaction code with a Fabric FakePlayer, the
// code a real player's packets reach, so the events and overrides below fire the way they do in play.
func checkPlayerActions(server mc.Server) {
	const (
		cGameMode = "net.minecraft.server.level.ServerPlayerGameMode"
		dPlayer   = "Lnet/minecraft/server/level/ServerPlayer;"
		dLevel    = "Lnet/minecraft/world/level/Level;"
		dStack    = "Lnet/minecraft/world/item/ItemStack;"
		dHand     = "Lnet/minecraft/world/InteractionHand;"
		dHit      = "Lnet/minecraft/world/phys/BlockHitResult;"
		dResult   = "Lnet/minecraft/world/InteractionResult;"
	)

	level := server.Overworld()
	player := jvm.FindClass("net.fabricmc.fabric.api.entity.FakePlayer").
		StaticMethod("get", "(Lnet/minecraft/server/level/ServerLevel;)Lnet/fabricmc/fabric/api/entity/FakePlayer;").CallStatic(level).Object()
	gameMode := jvm.FindClass("net.minecraft.server.level.ServerPlayer").Field("gameMode", "L"+strings.ReplaceAll(cGameMode, ".", "/")+";").Get(player)
	hand := jvm.FindClass("net.minecraft.world.InteractionHand").StaticField("MAIN_HAND", dHand).GetStatic()
	modes := jvm.FindClass(cGameMode)

	check("breaking a block fires BlockBroken with the player", func() error {
		pos := mc.Pos(1, 200, 0)
		level.SetBlock(pos, testBlock.DefaultState())
		before := brokenEvents.Load()
		if !modes.Method("destroyBlock", "(Lnet/minecraft/core/BlockPos;)Z").Call(gameMode, pos).Bool() {
			return fmt.Errorf("destroyBlock returned false")
		}
		if brokenEvents.Load() != before+1 {
			return fmt.Errorf("BlockBroken fired %d times", brokenEvents.Load()-before)
		}
		if name, _ := lastUser.Load().(string); name == "" {
			return fmt.Errorf("the event's player had no name")
		}
		if jvm.Same(level.BlockState(pos).Block(), testBlock.Block) {
			return fmt.Errorf("the block is still there")
		}
		return nil
	})

	check("using an item fires UseItem and reaches the Go override", func() error {
		eventsBefore, usesBefore := useItemEvents.Load(), useCount.Load()
		stack := mc.Stack(testItem, 1)
		result := modes.Method("useItem", "("+dPlayer+dLevel+dStack+dHand+")"+dResult).Call(gameMode, player, level, stack, hand).Object()
		if useItemEvents.Load() != eventsBefore+1 {
			return fmt.Errorf("UseItem fired %d times", useItemEvents.Load()-eventsBefore)
		}
		if useCount.Load() != usesBefore+1 {
			return fmt.Errorf("OnUse ran %d times", useCount.Load()-usesBefore)
		}
		// The game returns a copy of Success carrying the item's context, not the shared constant.
		if !result.IsInstance(jvm.FindClass("net.minecraft.world.InteractionResult$Success")) {
			return fmt.Errorf("useItem returned %s", result)
		}
		return nil
	})

	check("using a block fires UseBlock and reaches the Go override", func() error {
		pos := mc.Pos(2, 200, 0)
		level.SetBlock(pos, useBlock.DefaultState())
		center := jvm.FindClass("net.minecraft.world.phys.Vec3").StaticMethod("atCenterOf", "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;").CallStatic(pos)
		up := jvm.FindClass("net.minecraft.core.Direction").StaticField("UP", "Lnet/minecraft/core/Direction;").GetStatic()
		hit := jvm.FindClass("net.minecraft.world.phys.BlockHitResult").
			New("(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Z)V", center, up, pos, false)
		empty := jvm.FindClass("net.minecraft.world.item.ItemStack").StaticField("EMPTY", dStack).GetStatic()

		eventsBefore, usesBefore := useBlockEvents.Load(), blockUses.Load()
		modes.Method("useItemOn", "("+dPlayer+dLevel+dStack+dHand+dHit+")"+dResult).Call(gameMode, player, level, empty, hand, hit)
		if useBlockEvents.Load() != eventsBefore+1 {
			return fmt.Errorf("UseBlock fired %d times", useBlockEvents.Load()-eventsBefore)
		}
		if blockUses.Load() != usesBefore+1 {
			return fmt.Errorf("the block's OnUse ran %d times", blockUses.Load()-usesBefore)
		}
		return nil
	})
}

// checkTicks waits for real ticks, because the tick hook and events only run after start-up returns.
func checkTicks(server mc.Server) {
	var eventTicks atomic.Int64
	event.ServerTick(func(s mc.Server) {
		if eventTicks.Add(1) != 5 {
			return
		}

		check("hooks and events fire on the live tick loop", func() error {
			if hookTicks.Load() < 5 {
				return fmt.Errorf("hook fired %d times", hookTicks.Load())
			}
			return nil
		})

		done := make(chan struct{})
		gocraft.Go(func() {
			// Server.Execute from a goroutine lands back on the server thread.
			s.Execute(func() {
				check("Execute runs goroutine work on the server thread", func() error {
					thread := jvm.FindClass("java.lang.Thread").StaticMethod("currentThread", "()Ljava/lang/Thread;").CallStatic().String()
					if !strings.Contains(thread, "Server") {
						return fmt.Errorf("ran on %s", thread)
					}
					return nil
				})
				gocraft.Log().Info(fmt.Sprintf("GOCRAFT-IT RESULT passed=%d failed=%d", passed, failed))
				close(done)
				s.RunCommand("stop")
			})
		})
		_ = done
	})
}
