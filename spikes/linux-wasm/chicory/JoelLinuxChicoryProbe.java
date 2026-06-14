import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportGlobal;
import com.dylibso.chicory.runtime.ImportTable;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.Import;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.TableImport;
import com.dylibso.chicory.wasm.types.TableLimits;
import com.dylibso.chicory.wasm.types.Value;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class JoelLinuxChicoryProbe {

  private static final int PAGE_SIZE = 65536;
  private static final long ENOSYS = -38L;
  private static final long[] EMPTY = new long[0];

  private final Map<String, Integer> callbackCounts = new LinkedHashMap<>();
  private final Path wasmPath;
  private final Path initrdPath;
  private final String cmdline;
  private final int initialMemoryPages;
  private final byte[] stdinBytes;
  private final String exitOnOutput;
  private final Engine engine;
  private final InterpreterFallback compilerFallback;
  private final SecureRandom random = new SecureRandom();
  private final Object schedulerLock = new Object();
  private final StringBuilder consoleOutput = new StringBuilder();
  private final Map<Long, Runner> runnersByTask = new LinkedHashMap<>();
  private final IdentityHashMap<Instance, Runner> runnersByInstance = new IdentityHashMap<>();
  private final Map<String, Function<Instance, Machine>> machineFactoriesByDigest = new LinkedHashMap<>();
  private final Map<String, JoelLinuxLinkedUserModule> aotUserModulesBySha256 = new LinkedHashMap<>();
  private final Path executableDumpDir = Path.of(
    "spikes/linux-wasm/build/joel-demo/user-executables"
  );
  private WasmModule module;
  private ImportValues importValues;
  private Memory memory;
  private Function<Instance, Machine> vmlinuxMachineFactory;
  private RuntimeException fatalFailure;
  private int executableDumpCount;
  private int stdinOffset;

  private JoelLinuxChicoryProbe(Args args) {
    this.wasmPath = args.wasmPath;
    this.initrdPath = args.initrdPath;
    this.cmdline = args.cmdline;
    this.initialMemoryPages = args.initialMemoryPages;
    this.stdinBytes = args.stdinText.getBytes(StandardCharsets.UTF_8);
    this.exitOnOutput = args.exitOnOutput;
    this.engine = args.engine;
    this.compilerFallback = args.compilerFallback;
  }

  public static void main(String[] argv) throws Exception {
    Args args = Args.parse(argv);
    new JoelLinuxChicoryProbe(args).run();
  }

  private void run() throws IOException {
    log("wasm=" + wasmPath);
    log("initrd=" + initrdPath);
    log("cmdline=\"" + cmdline + "\"");
    log("engine=" + engine.name().toLowerCase(Locale.ROOT));

    module = Parser.parse(wasmPath);
    log(
      "module imports=" +
      module.importSection().importCount() +
      " exports=" +
      module.exportSection().exportCount() +
      " types=" +
      module.typeSection().typeCount()
    );
    vmlinuxMachineFactory = machineFactoryFor("vmlinux", module, true);
    loadAotUserMachineFactories();

    Store store = new Store();
    int functionImports = 0;
    int sysStubs = 0;

    for (int i = 0; i < module.importSection().importCount(); i++) {
      Import imported = module.importSection().getImport(i);
      if (imported instanceof MemoryImport memoryImport) {
        addMemoryImport(store, memoryImport);
      } else if (imported instanceof FunctionImport functionImport) {
        FunctionType type = module.typeSection().getType(functionImport.typeIndex());
        store.addFunction(
          new HostFunction(
            imported.module(),
            imported.name(),
            type,
            (instance, args) -> callHost(instance, imported.name(), type, args)
          )
        );
        functionImports++;
        if (imported.name().startsWith("sys_")) {
          sysStubs++;
        }
      } else {
        throw new IllegalStateException("Unsupported import: " + imported);
      }
    }

    log(
      "host imports: functions=" +
      functionImports +
      " sys_stubs=" +
      sysStubs +
      " memory_pages=" +
      memory.pages() +
      " shared=" +
      memory.shared()
    );

    importValues = store.toImportValues();
    Instance instance = instantiateVmlinux();
    Runner primary = registerRunner(
      "CPU 0 [boot+idle]",
      exportedGlobal(instance, "init_task"),
      instance
    );

    patchBootInputs(instance);

    log("calling _start");
    long start = System.nanoTime();
    instance.export("_start").apply();
    log("returned from _start in " + ((System.nanoTime() - start) / 1_000_000) + "ms");
    primary.stopped = true;
  }

  private void addMemoryImport(Store store, MemoryImport memoryImport) {
    if (!"env".equals(memoryImport.module()) || !"memory".equals(memoryImport.name())) {
      throw new IllegalStateException("Unexpected memory import: " + memoryImport);
    }

    MemoryLimits required = memoryImport.limits();
    MemoryLimits limits = new MemoryLimits(
      Math.max(initialMemoryPages, required.initialPages()),
      required.maximumPages(),
      required.shared()
    );
    this.memory = new ByteArrayMemory(limits);
    store.addMemory(new ImportMemory(memoryImport.module(), memoryImport.name(), memory));
  }

  private Instance instantiateVmlinux() {
    Instance.Builder builder = Instance
      .builder(module)
      .withImportValues(importValues)
      .withStart(false);
    if (vmlinuxMachineFactory != null) {
      builder.withMachineFactory(vmlinuxMachineFactory);
    }
    return builder.build();
  }

  private Function<Instance, Machine> machineFactoryFor(
    String label,
    WasmModule wasmModule,
    boolean kernelModule
  ) {
    if (engine == Engine.INTERPRETER) {
      return null;
    }
    if (engine == Engine.AOT) {
      if (!kernelModule) {
        throw new IllegalStateException(
          "AOT user module " + label + " must be selected from static SHA-256 links"
        );
      }
      return loadAotMachineFactory();
    }

    String digest = wasmModule.digest();
    if (digest != null) {
      Function<Instance, Machine> cached = machineFactoriesByDigest.get(digest);
      if (cached != null) {
        return cached;
      }
    }

    long start = System.nanoTime();
    Function<Instance, Machine> factory = MachineFactoryCompiler
      .builder(wasmModule)
      .withInterpreterFallback(compilerFallback)
      .compile();
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    log(
      "compiled " +
      label +
      " functions=" +
      wasmModule.functionSection().functionCount() +
      " fallback=" +
      compilerFallback.name().toLowerCase(Locale.ROOT) +
      " in " +
      elapsedMillis +
      "ms"
    );

    if (digest != null) {
      machineFactoriesByDigest.put(digest, factory);
    }
    return factory;
  }

  private Function<Instance, Machine> loadAotMachineFactory() {
    Function<Instance, Machine> factory = JoelLinuxStaticAotModules.vmlinuxMachineFactory();
    if (factory == null) {
      throw new IllegalStateException(
        "Static AOT vmlinux machine is not linked. Build benchmarks with -Dlinux.wasm.aot=true."
      );
    }
    log("loaded statically linked AOT vmlinux machine");
    return factory;
  }

  private void loadAotUserMachineFactories() {
    if (engine != Engine.AOT) {
      return;
    }

    for (var entry : JoelLinuxStaticAotModules.userModulesBySha256().entrySet()) {
      aotUserModulesBySha256.put(
        entry.getKey().toLowerCase(Locale.ROOT),
        entry.getValue()
      );
    }
    log("loaded statically linked AOT user modules=" + aotUserModulesBySha256.size());
  }

  private void patchBootInputs(Instance instance) throws IOException {
    int cmdlinePtr = exportedGlobal(instance, "boot_command_line");
    memory.writeCString(cmdlinePtr, cmdline, StandardCharsets.UTF_8);
    log("boot_command_line @ 0x" + Integer.toHexString(cmdlinePtr));

    byte[] initrd = Files.readAllBytes(initrdPath);
    int initrdPages = (initrd.length + PAGE_SIZE - 1) / PAGE_SIZE;
    int oldPages = memory.grow(initrdPages);
    if (oldPages < 0) {
      throw new IllegalStateException("memory.grow failed for initrd pages=" + initrdPages);
    }

    int initrdStart = oldPages * PAGE_SIZE;
    int initrdEnd = initrdStart + initrd.length;
    memory.write(initrdStart, initrd);
    memory.writeI32(exportedGlobal(instance, "initrd_start"), initrdStart);
    memory.writeI32(exportedGlobal(instance, "initrd_end"), initrdEnd);
    log(
      "initrd bytes=" +
      initrd.length +
      " pages=" +
      initrdPages +
      " range=0x" +
      Integer.toHexString(initrdStart) +
      "..0x" +
      Integer.toHexString(initrdEnd)
    );
  }

  private int exportedGlobal(Instance instance, String name) {
    return Math.toIntExact(instance.exports().global(name).getValue());
  }

  private long[] callHost(
    Instance instance,
    String name,
    FunctionType type,
    long[] args
  ) {
    return switch (name) {
      case "wasm_cpu_clock_get_monotonic" -> new long[] {
        Instant.now().toEpochMilli() * 1_000_000L,
      };
      case "wasm_random_get_bytes" -> randomGetBytes(instance.memory(), args);
      case "wasm_driver_hvc_put" -> hvcPut(instance.memory(), args);
      case "wasm_driver_hvc_get" -> hvcGet(instance.memory(), args);
      case "wasm_dump_stacktrace" -> dumpStackTrace(instance.memory(), args);
      case "wasm_create_and_run_task" -> createAndRunTask(
        currentRunner(instance),
        instance.memory(),
        args
      );
      case "wasm_serialize_tasks" -> serializeTasks(currentRunner(instance), args);
      case "wasm_load_executable" -> loadExecutable(currentRunner(instance), args);
      case "wasm_start_cpu" -> startCpu(args);
      case "wasm_stop_cpu" -> stopCpu(args);
      case "wasm_release_task" -> releaseTask(args);
      case "wasm_user_mode_tail" -> userModeTail(args);
      case "wasm_panic" -> panic(instance.memory(), args);
      default -> {
        if (name.startsWith("sys_")) {
          note(name, "stubbed " + Arrays.toString(args) + " -> -ENOSYS");
          yield defaultReturn(type, ENOSYS);
        }
        throw new IllegalStateException("Unexpected host call: " + name);
      }
    };
  }

  private long[] hvcPut(Memory memory, long[] args) {
    int buffer = Math.toIntExact(args[0]);
    int count = Math.toIntExact(args[1]);
    byte[] bytes = memory.readBytes(buffer, count);
    String text = new String(bytes, StandardCharsets.UTF_8);
    System.out.print(text);
    System.out.flush();
    maybeExitOnOutput(text);
    return new long[] { count };
  }

  private void maybeExitOnOutput(String text) {
    if (exitOnOutput.isEmpty()) {
      return;
    }

    synchronized (consoleOutput) {
      consoleOutput.append(text);
      if (consoleOutput.indexOf(exitOnOutput) >= 0) {
        log("exit_on_output matched: " + exitOnOutput);
        System.exit(0);
      }
      if (consoleOutput.length() > 1_000_000) {
        consoleOutput.delete(0, consoleOutput.length() - exitOnOutput.length());
      }
    }
  }

  private long[] randomGetBytes(Memory memory, long[] args) {
    int buffer = Math.toIntExact(args[0]);
    int count = Math.toIntExact(args[1]);
    if (count < 0 || count > 0x10000) {
      note("wasm_random_get_bytes", "rejecting count=" + count);
      return new long[] { -1 };
    }

    byte[] bytes = new byte[count];
    random.nextBytes(bytes);
    memory.write(buffer, bytes);
    note("wasm_random_get_bytes", "wrote " + count + " byte(s)");
    return new long[] { count };
  }

  private long[] hvcGet(Memory memory, long[] args) {
    int buffer = Math.toIntExact(args[0]);
    int count = Math.toIntExact(args[1]);
    int copied = 0;
    synchronized (schedulerLock) {
      if (stdinOffset < stdinBytes.length) {
        copied = Math.min(count, stdinBytes.length - stdinOffset);
        memory.write(buffer, stdinBytes, stdinOffset, copied);
        stdinOffset += copied;
      }
    }

    if (copied > 0) {
      note("wasm_driver_hvc_get", "copied " + copied + " stdin byte(s)");
    } else {
      note("wasm_driver_hvc_get", "no stdin available, returning 0");
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    return new long[] { copied };
  }

  private long[] dumpStackTrace(Memory memory, long[] args) {
    int stackTrace = Math.toIntExact(args[0]);
    int maxSize = Math.toIntExact(args[1]);
    if (maxSize <= 0) {
      return EMPTY;
    }
    byte[] text = "Chicory host stack trace unavailable\n".getBytes(StandardCharsets.UTF_8);
    int len = Math.min(text.length, maxSize - 1);
    memory.write(stackTrace, text, 0, len);
    memory.writeByte(stackTrace + len, (byte) 0);
    return EMPTY;
  }

  private long[] createAndRunTask(Runner current, Memory memory, long[] args) {
    long prevTask = args[0];
    long newTask = args[1];
    String name = readCString(memory, args[2]);
    long binStart = args[3];
    long binEnd = args[4];
    note(
      "wasm_create_and_run_task",
      "prev=0x" +
      Long.toHexString(prevTask) +
      " new=0x" +
      Long.toHexString(newTask) +
      " name=" +
      name +
      " executable_bytes=" +
      Math.max(0, binEnd - binStart)
    );
    Runner runner = makeTaskRunner(prevTask, newTask, name);
    if (binStart != 0) {
      runner.executable = new Executable(binStart, binEnd, args[5], args[6]);
    }
    return new long[] { switchToNewRunner(current, prevTask, newTask, runner) };
  }

  private long[] serializeTasks(Runner current, long[] args) {
    long prevTask = args[0];
    long nextTask = args[1];
    note(
      "wasm_serialize_tasks",
      "prev=0x" + Long.toHexString(prevTask) + " next=0x" + Long.toHexString(nextTask)
    );
    return new long[] { switchToExistingRunner(current, prevTask, nextTask) };
  }

  private long[] loadExecutable(Runner current, long[] args) {
    long binStart = args[0];
    long binEnd = args[1];
    note(
      "wasm_load_executable",
      "range=0x" +
      Long.toHexString(binStart) +
      "..0x" +
      Long.toHexString(binEnd) +
      " bytes=" +
      Math.max(0, binEnd - binStart)
    );
    current.executable = new Executable(binStart, binEnd, args[2], args[3]);
    dumpExecutable(current, current.executable);
    return EMPTY;
  }

  private long[] startCpu(long[] args) {
    long cpu = args[0];
    long idleTask = args[1];
    long startArg = args.length > 2 ? args[2] : idleTask;
    note(
      "wasm_start_cpu",
      "cpu=" +
      cpu +
      " idle_task=0x" +
      Long.toHexString(idleTask) +
      " start_arg=0x" +
      Long.toHexString(startArg)
    );
    Runner runner = registerRunner(
      "CPU " + cpu + " [boot+idle]",
      idleTask,
      instantiateVmlinux()
    );
    Thread thread = new Thread(
      () -> runSecondaryCpu(runner, startArg),
      "joel-chicory-cpu-" + cpu
    );
    thread.start();
    return EMPTY;
  }

  private long[] stopCpu(long[] args) {
    note("wasm_stop_cpu", "cpu=" + args[0]);
    return EMPTY;
  }

  private long[] releaseTask(long[] args) {
    long deadTask = args[0];
    note("wasm_release_task", "dead_task=0x" + Long.toHexString(deadTask));
    synchronized (schedulerLock) {
      Runner dead = runnersByTask.remove(deadTask);
      if (dead != null) {
        runnersByInstance.remove(dead.instance);
        dead.stopped = true;
      }
      schedulerLock.notifyAll();
    }
    return EMPTY;
  }

  private long[] userModeTail(long[] args) {
    note("wasm_user_mode_tail", "flow=" + args[0]);
    if (args[0] == -1L) {
      throw new ReloadProgram();
    }
    return EMPTY;
  }

  private long[] panic(Memory memory, long[] args) {
    throw new KernelPanic(readCString(memory, args[0]));
  }

  private long[] defaultReturn(FunctionType type, long value) {
    if (type.returns().isEmpty()) {
      return EMPTY;
    }
    long[] results = new long[type.returns().size()];
    Arrays.fill(results, value);
    return results;
  }

  private String readCString(Memory memory, long ptr) {
    if (ptr == 0) {
      return "<null>";
    }
    try {
      return memory.readCString(Math.toIntExact(ptr), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return "<cstring@0x" + Long.toHexString(ptr) + " unreadable: " + e + ">";
    }
  }

  private void note(String callback, String message) {
    int count = callbackCounts.merge(callback, 1, Integer::sum);
    if (count <= 8 || count == 16 || count == 32 || count == 64) {
      log(callback + "[" + count + "]: " + message);
    }
  }

  private static void log(String message) {
    System.err.println("[joel-chicory] " + message);
  }

  private Runner makeTaskRunner(long prevTask, long newTask, String name) {
    Instance taskInstance = instantiateVmlinux();
    return registerRunner("task " + name + " [0x" + Long.toHexString(newTask) + "]", newTask, taskInstance);
  }

  private Runner registerRunner(String name, long taskId, Instance instance) {
    Runner runner = new Runner(name, taskId, instance);
    synchronized (schedulerLock) {
      runnersByTask.put(taskId, runner);
      runnersByInstance.put(instance, runner);
      log(name + " registered task=0x" + Long.toHexString(taskId));
    }
    return runner;
  }

  private Runner currentRunner(Instance instance) {
    synchronized (schedulerLock) {
      Runner runner = runnersByInstance.get(instance);
      if (runner == null) {
        throw new IllegalStateException("No runner registered for instance " + instance);
      }
      return runner;
    }
  }

  private long switchToNewRunner(
    Runner current,
    long prevTask,
    long newTask,
    Runner newRunner
  ) {
    synchronized (schedulerLock) {
      current.runnable = false;
    }

    Thread thread = new Thread(
      () -> runTask(newRunner, prevTask, newTask),
      "joel-chicory-" + Long.toHexString(newTask)
    );
    thread.start();

    return awaitWake(current);
  }

  private long switchToExistingRunner(Runner current, long prevTask, long nextTask) {
    synchronized (schedulerLock) {
      current.runnable = false;
      Runner next = runnersByTask.get(nextTask);
      if (next == null) {
        throw new IllegalStateException(
          "Cannot switch to unknown task 0x" + Long.toHexString(nextTask)
        );
      }
      next.lastTask = prevTask;
      next.runnable = true;
      schedulerLock.notifyAll();
    }
    return awaitWake(current);
  }

  private long awaitWake(Runner current) {
    synchronized (schedulerLock) {
      while (!current.runnable && fatalFailure == null && !current.stopped) {
        try {
          schedulerLock.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      if (fatalFailure != null) {
        throw fatalFailure;
      }
      if (current.stopped) {
        throw new TaskStopped(current.name + " stopped while waiting");
      }
      return current.lastTask;
    }
  }

  private void runTask(Runner runner, long prevTask, long newTask) {
    log(
      runner.name +
      " ret_from_fork(prev=0x" +
      Long.toHexString(prevTask) +
      ", new=0x" +
      Long.toHexString(newTask) +
      ")"
    );
    try {
      long[] result = runner.instance.export("ret_from_fork").apply(prevTask, newTask);
      boolean shouldCallCloneCallback = result.length > 0 && result[0] != 0;
      if (runner.executable == null) {
        throw new StopExecution(
          runner.name +
          " returned from ret_from_fork with no user executable" +
          " clone_callback=" +
          shouldCallCloneCallback
        );
      }
      runUserExecutable(runner, shouldCallCloneCallback);
    } catch (TaskStopped e) {
      log(runner.name + " stopped");
      synchronized (schedulerLock) {
        runner.stopped = true;
        schedulerLock.notifyAll();
      }
    } catch (RuntimeException e) {
      synchronized (schedulerLock) {
        if (fatalFailure == null) {
          fatalFailure = e;
        }
        runner.stopped = true;
        schedulerLock.notifyAll();
      }
    }
  }

  private void runSecondaryCpu(Runner runner, long startStack) {
    log(runner.name + " _start_secondary(stack=0x" + Long.toHexString(startStack) + ")");
    try {
      runner.instance.export("_start_secondary").apply(startStack);
      throw new StopExecution(runner.name + " _start_secondary returned");
    } catch (TaskStopped e) {
      log(runner.name + " stopped");
      synchronized (schedulerLock) {
        runner.stopped = true;
        schedulerLock.notifyAll();
      }
    } catch (RuntimeException e) {
      synchronized (schedulerLock) {
        if (fatalFailure == null) {
          fatalFailure = e;
        }
        runner.stopped = true;
        schedulerLock.notifyAll();
      }
    }
  }

  private void runUserExecutable(Runner runner, boolean shouldCallCloneCallback) {
    while (true) {
      Executable executable = runner.executable;
      if (executable == null) {
        throw new StopExecution(runner.name + " has no user executable to run");
      }

      byte[] executableBytes = memory.readBytes(
        Math.toIntExact(executable.binStart),
        Math.toIntExact(executable.binEnd - executable.binStart)
      );
      String executableSha256 = sha256Hex(executableBytes);
      WasmModule userModule;
      Function<Instance, Machine> userMachineFactory;
      if (engine == Engine.AOT) {
        JoelLinuxLinkedUserModule linkedModule = aotUserModulesBySha256.get(executableSha256);
        if (linkedModule == null) {
          throw new IllegalStateException(
            "No statically linked AOT user module for " +
            runner.name +
            " sha256=" +
            executableSha256
          );
        }
        userModule = linkedModule.wasmModule();
        userMachineFactory = linkedModule.machineFactory();
        log(
          "using statically linked AOT user module for " +
          runner.name +
          " sha256=" +
          executableSha256
        );
      } else {
        userModule = Parser.parse(executableBytes);
        userMachineFactory = machineFactoryFor(
          "user " + runner.name,
          userModule,
          false
        );
      }
      Instance userInstance = instantiateUserExecutable(
        runner,
        userModule,
        executable,
        shouldCallCloneCallback,
        userMachineFactory
      );

      try {
        callIfPresent(userModule, userInstance, "__wasm_apply_data_relocs");
        if (shouldCallCloneCallback) {
          long tlsBase = runner.instance.export("get_user_tls_base").apply()[0];
          userInstance.export("__set_tls_base").apply(tlsBase);
          userInstance.export("__libc_clone_callback").apply();
          throw new StopExecution("__libc_clone_callback returned for " + runner.name);
        }

        callIfPresent(userModule, userInstance, "__wasm_call_ctors");
        log(runner.name + " entering user _start");
        userInstance.export("_start").apply();
        throw new StopExecution("user _start returned for " + runner.name);
      } catch (RuntimeException e) {
        ReloadProgram reload = findCause(e, ReloadProgram.class);
        if (reload != null) {
          log(runner.name + " reloading user executable after exec");
          shouldCallCloneCallback = false;
          continue;
        }
        throw e;
      }
    }
  }

  private Instance instantiateUserExecutable(
    Runner runner,
    WasmModule userModule,
    Executable executable,
    boolean shouldCallCloneCallback,
    Function<Instance, Machine> userMachineFactory
  ) {
    Store userStore = new Store();
    long stackPointer = runner.instance.export("get_user_stack_pointer").apply()[0];
    long tlsBase = runner.instance.export("get_user_tls_base").apply()[0];

    for (int i = 0; i < userModule.importSection().importCount(); i++) {
      Import imported = userModule.importSection().getImport(i);
      if (imported instanceof MemoryImport memoryImport) {
        userStore.addMemory(new ImportMemory(memoryImport.module(), memoryImport.name(), memory));
      } else if (imported instanceof TableImport tableImport) {
        int tableInitial = Math.toIntExact(Math.max(4096, tableImport.limits().min()));
        Table table = new Table(tableImport.entryType(), new TableLimits(tableInitial));
        userStore.addTable(
          new ImportTable(
            tableImport.module(),
            tableImport.name(),
            new TableInstance(table, 0)
          )
        );
      } else if (imported instanceof GlobalImport globalImport) {
        userStore.addGlobal(
          new ImportGlobal(
            globalImport.module(),
            globalImport.name(),
            userGlobal(globalImport, executable, stackPointer)
          )
        );
      } else if (imported instanceof FunctionImport functionImport) {
        FunctionType type = userModule.typeSection().getType(functionImport.typeIndex());
        userStore.addFunction(userFunction(runner, imported.name(), type));
      } else {
        throw new IllegalStateException("Unsupported user import: " + imported);
      }
    }

    log(
      runner.name +
      " user imports=" +
      userModule.importSection().importCount() +
      " stack=0x" +
      Long.toHexString(stackPointer) +
      " tls=0x" +
      Long.toHexString(tlsBase) +
      " data=0x" +
      Long.toHexString(executable.dataStart) +
      " table=0x" +
      Long.toHexString(executable.tableStart) +
      " clone_callback=" +
      shouldCallCloneCallback
    );

    Instance.Builder builder = Instance
      .builder(userModule)
      .withImportValues(userStore.toImportValues())
      .withStart(false);
    if (userMachineFactory != null) {
      builder.withMachineFactory(userMachineFactory);
    }
    return builder.build();
  }

  private GlobalInstance userGlobal(
    GlobalImport globalImport,
    Executable executable,
    long stackPointer
  ) {
    return switch (globalImport.name()) {
      case "__stack_pointer" -> new GlobalInstance(
        Value.i32(stackPointer),
        MutabilityType.Var
      );
      case "__memory_base" -> new GlobalInstance(
        Value.i32(executable.dataStart),
        MutabilityType.Const
      );
      case "__table_base" -> new GlobalInstance(
        Value.i32(executable.tableStart),
        MutabilityType.Const
      );
      default -> throw new IllegalStateException("Unexpected user global import: " + globalImport);
    };
  }

  private HostFunction userFunction(Runner runner, String importName, FunctionType type) {
    if ("__wasm_abort".equals(importName)) {
      return new HostFunction(
        "env",
        importName,
        type,
        (instance, args) -> {
          throw new StopExecution("__wasm_abort from " + runner.name);
        }
      );
    }

    if (importName.startsWith("__wasm_syscall_")) {
      String kernelExport = importName.substring("__".length());
      return new HostFunction(
        "env",
        importName,
        type,
        (instance, args) -> runner.instance.export(kernelExport).apply(args)
      );
    }

    throw new IllegalStateException("Unexpected user function import: " + importName);
  }

  private void callIfPresent(WasmModule userModule, Instance userInstance, String exportName) {
    if (hasFunctionExport(userModule, exportName)) {
      userInstance.export(exportName).apply();
    }
  }

  private boolean hasFunctionExport(WasmModule module, String exportName) {
    for (int i = 0; i < module.exportSection().exportCount(); i++) {
      var exported = module.exportSection().getExport(i);
      if (
        exported.name().equals(exportName) &&
        exported.exportType() == ExternalType.FUNCTION
      ) {
        return true;
      }
    }
    return false;
  }

  private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
      current = current.getCause();
    }
    return null;
  }

  private String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b & 0xff));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is unavailable", e);
    }
  }

  private void dumpExecutable(Runner runner, Executable executable) {
    int start = Math.toIntExact(executable.binStart);
    int length = Math.toIntExact(executable.binEnd - executable.binStart);
    String safeName = runner.name.replaceAll("[^A-Za-z0-9._-]+", "_");
    int dumpIndex;
    synchronized (schedulerLock) {
      dumpIndex = ++executableDumpCount;
    }
    Path dump = executableDumpDir.resolve(
      String.format("%02d-%s.wasm", dumpIndex, safeName)
    );
    try {
      Files.createDirectories(executableDumpDir);
      Files.write(dump, memory.readBytes(start, length));
      log("dumped user executable " + dump + " bytes=" + length);
    } catch (IOException e) {
      throw new RuntimeException("Failed to dump user executable " + dump, e);
    }
  }

  private static final class Runner {

    private final String name;
    private final long taskId;
    private final Instance instance;
    private boolean runnable = true;
    private boolean stopped;
    private long lastTask;
    private Executable executable;

    private Runner(String name, long taskId, Instance instance) {
      this.name = name;
      this.taskId = taskId;
      this.instance = instance;
    }
  }

  private record Executable(long binStart, long binEnd, long dataStart, long tableStart) {}

  private static final class KernelPanic extends RuntimeException {

    private KernelPanic(String message) {
      super("Kernel panic: " + message);
    }
  }

  private static final class StopExecution extends RuntimeException {

    private StopExecution(String message) {
      super(message);
    }
  }

  private static final class TaskStopped extends RuntimeException {

    private TaskStopped(String message) {
      super(message);
    }
  }

  private static final class ReloadProgram extends RuntimeException {

    private ReloadProgram() {
      super("user executable reload requested");
    }
  }

  private enum Engine {
    INTERPRETER,
    RUNTIME_COMPILER,
    AOT;

    private static Engine parse(String value) {
      if ("compiler".equalsIgnoreCase(value)) {
        return RUNTIME_COMPILER;
      }
      try {
        return Engine.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
          "Unsupported engine " +
          value +
          ", expected interpreter, runtime-compiler, compiler, or aot",
          e
        );
      }
    }
  }

  private static final class Args {

    private Path wasmPath = Path.of(
      "spikes/linux-wasm/build/joel-demo/vmlinux.stripped.wasm"
    );
    private Path initrdPath = Path.of(
      "spikes/linux-wasm/build/joel-demo/initramfs.cpio.gz"
    );
    private String cmdline =
      "maxcpus=1 nohz_full=0 root=/dev/ram0 rootfstype=ramfs init=/init console=hvc console=ttyS0";
    private int initialMemoryPages = 30;
    private String stdinText = "";
    private String exitOnOutput = "";
    private Engine engine = Engine.INTERPRETER;
    private InterpreterFallback compilerFallback = InterpreterFallback.WARN;

    private static Args parse(String[] argv) {
      Args args = new Args();
      for (int i = 0; i < argv.length; i++) {
        switch (argv[i]) {
          case "--wasm" -> args.wasmPath = Path.of(requireValue(argv, ++i, "--wasm"));
          case "--initrd" -> args.initrdPath = Path.of(requireValue(argv, ++i, "--initrd"));
          case "--cmdline" -> args.cmdline = requireValue(argv, ++i, "--cmdline");
          case "--memory-pages" -> args.initialMemoryPages =
            Integer.parseInt(requireValue(argv, ++i, "--memory-pages"));
          case "--stdin-text" -> args.stdinText = requireValue(argv, ++i, "--stdin-text");
          case "--exit-on-output" -> args.exitOnOutput =
            requireValue(argv, ++i, "--exit-on-output");
          case "--engine" -> args.engine = Engine.parse(requireValue(argv, ++i, "--engine"));
          case "--compiler-fallback" -> args.compilerFallback =
            parseCompilerFallback(requireValue(argv, ++i, "--compiler-fallback"));
          case "--help" -> {
            usage();
            System.exit(0);
          }
          default -> throw new IllegalArgumentException(
            "Unknown argument " + argv[i] + "\n\n" + usageText()
          );
        }
      }
      return args;
    }

    private static InterpreterFallback parseCompilerFallback(String value) {
      try {
        return InterpreterFallback.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
          "Unsupported compiler fallback " + value + ", expected silent, warn, or fail",
          e
        );
      }
    }

    private static String requireValue(String[] argv, int index, String flag) {
      if (index >= argv.length) {
        throw new IllegalArgumentException(flag + " requires a value");
      }
      return argv[index];
    }

    private static void usage() {
      System.err.println(usageText());
    }

    private static String usageText() {
      return String.join(
        System.lineSeparator(),
        "Usage: JoelLinuxChicoryProbe [options]",
        "",
        "Options:",
        "  --wasm PATH          Joel vmlinux.wasm path",
        "  --initrd PATH        initramfs.cpio.gz path",
        "  --cmdline TEXT       Linux boot command line",
        "  --memory-pages N     initial imported memory pages, default 30",
        "  --stdin-text TEXT    bytes returned by the hvc console input callback",
        "  --exit-on-output TEXT",
        "                       exit successfully after this console text appears",
        "  --engine NAME        interpreter, runtime-compiler, or aot, default interpreter",
        "  --compiler-fallback NAME",
        "                       silent, warn, or fail, default warn"
      );
    }
  }
}

record JoelLinuxLinkedUserModule(
  WasmModule wasmModule,
  Function<Instance, Machine> machineFactory
) {}
