package com.hubspot.boomslang.benchmarks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(
  value = 1,
  jvmArgs = {
    "-XX:+UseG1GC",
    "-XX:CompileThreshold=1500",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:-DontCompileHugeMethods",
    "-Xmx2g",
  }
)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
public class LinuxWasmScriptBenchmark {

  private static final String BENCHMARK_CMDLINE =
    "maxcpus=1 nohz_full=0 root=/dev/ram0 rootfstype=ramfs init=/init console=hvc console=ttyS0";
  private static final AtomicInteger MARKER_COUNTER = new AtomicInteger();

  private Path repoRoot;
  private Path wasmPath;
  private Path initrdPath;
  private Path javaBin;
  private String childClasspath;
  private Duration timeout;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    repoRoot = findRepoRoot();
    wasmPath = findWasmPath(repoRoot);
    initrdPath = findInitrdPath(repoRoot);
    javaBin = Path.of(System.getProperty("java.home"), "bin", javaExecutableName());
    timeout = Duration.ofSeconds(configuredTimeoutSeconds());
    childClasspath = compileProbe(repoRoot);
  }

  @Benchmark
  public void bootAndEcho(Blackhole blackhole) throws Exception {
    blackhole.consume(runScript("echo linux-wasm-jmh-echo"));
  }

  @Benchmark
  public void bootAndShellLoop(Blackhole blackhole) throws Exception {
    blackhole.consume(
      runScript(
        String.join(
          "\n",
          "i=0",
          "while [ \"$i\" -lt 100 ]; do",
          "  i=$((i + 1))",
          "done",
          "echo loop-$i"
        )
      )
    );
  }

  @Benchmark
  public void bootAndPipeline(Blackhole blackhole) throws Exception {
    blackhole.consume(
      runScript(
        String.join(
          "\n",
          "printf 'alpha\\nbeta\\ngamma\\n' | grep a | wc -l"
        )
      )
    );
  }

  public static void main(String[] args) throws Exception {
    var builder = new OptionsBuilder()
      .include(LinuxWasmScriptBenchmark.class.getSimpleName());
    String[] forkedProperties = forkedJvmProperties();
    if (forkedProperties.length > 0) {
      builder.jvmArgsAppend(forkedProperties);
    }
    Options opt = builder.build();
    new Runner(opt).run();
  }

  private String runScript(String script) throws Exception {
    String marker =
      "__BOOMSLANG_LINUX_WASM_JMH_DONE_" + MARKER_COUNTER.incrementAndGet() + "__";
    String stdin = script + "\necho " + marker + "\n";
    Path output = Files.createTempFile("boomslang-linux-wasm-jmh-", ".log");

    List<String> command = new ArrayList<>();
    command.add(javaBin.toString());
    command.add("-Xmx2g");
    command.add("-cp");
    command.add(childClasspath);
    command.add("JoelLinuxChicoryProbe");
    command.add("--wasm");
    command.add(wasmPath.toString());
    command.add("--initrd");
    command.add(initrdPath.toString());
    command.add("--cmdline");
    command.add(BENCHMARK_CMDLINE);
    command.add("--stdin-text");
    command.add(stdin);
    command.add("--exit-on-output");
    command.add(marker);

    Process process = new ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .redirectOutput(output.toFile())
      .start();

    boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }

    String text = Files.readString(output, StandardCharsets.UTF_8);
    Files.deleteIfExists(output);

    if (!exited) {
      throw new IllegalStateException(
        "Linux WASM script benchmark timed out after " +
        timeout +
        "\n" +
        tail(text)
      );
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
        "Linux WASM script benchmark exited " +
        process.exitValue() +
        "\n" +
        tail(text)
      );
    }
    if (!text.contains(marker)) {
      throw new IllegalStateException(
        "Linux WASM script benchmark did not observe completion marker " +
        marker +
        "\n" +
        tail(text)
      );
    }

    return text;
  }

  private static String compileProbe(Path repoRoot) throws IOException {
    Path source = repoRoot.resolve("spikes/linux-wasm/chicory/JoelLinuxChicoryProbe.java");
    Path classes = repoRoot.resolve("benchmarks/target/linux-wasm-chicory-classes");
    Files.createDirectories(classes);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
        "LinuxWasmScriptBenchmark requires a JDK, not a JRE, to compile " + source
      );
    }

    String classpath = System.getProperty("java.class.path");
    int result = compiler.run(
      null,
      null,
      null,
      "-proc:none",
      "-cp",
      classpath,
      "-d",
      classes.toString(),
      source.toString()
    );
    if (result != 0) {
      throw new IllegalStateException(
        "Failed to compile JoelLinuxChicoryProbe.java for Linux WASM benchmarks"
      );
    }

    return classes + File.pathSeparator + classpath;
  }

  private static Path findRepoRoot() {
    String configuredRoot = configuredValue(
      "linux.wasm.bench.root",
      "LINUX_WASM_BENCH_ROOT"
    );
    if (configuredRoot != null && !configuredRoot.isBlank()) {
      return Path.of(configuredRoot).toAbsolutePath().normalize();
    }

    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("spikes/linux-wasm/chicory/JoelLinuxChicoryProbe.java"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
      "Could not find repo root. Pass -Dlinux.wasm.bench.root=/path/to/boomslang."
    );
  }

  private static Path findWasmPath(Path repoRoot) {
    String configured = configuredValue(
      "linux.wasm.bench.wasm",
      "LINUX_WASM_BENCH_WASM"
    );
    if (configured != null && !configured.isBlank()) {
      return requireFile(Path.of(configured), "linux.wasm.bench.wasm");
    }

    return firstExisting(
      repoRoot.resolve("spikes/linux-wasm/build/joel-compiled/vmlinux.stripped.wasm"),
      repoRoot.resolve("spikes/linux-wasm/build/joel-demo/vmlinux.stripped.wasm"),
      repoRoot.resolve("spikes/linux-wasm/build/joel-demo/vmlinux.wasm")
    ).orElseThrow(() ->
      new IllegalStateException(
        "Linux WASM benchmark artifact is missing. Run `make -C spikes/linux-wasm fetch-joel-demo` " +
        "or configure linux.wasm.bench.wasm / LINUX_WASM_BENCH_WASM."
      )
    );
  }

  private static Path findInitrdPath(Path repoRoot) {
    String configured = configuredValue(
      "linux.wasm.bench.initrd",
      "LINUX_WASM_BENCH_INITRD"
    );
    if (configured != null && !configured.isBlank()) {
      return requireFile(Path.of(configured), "linux.wasm.bench.initrd");
    }

    return firstExisting(
      repoRoot.resolve("spikes/linux-wasm/build/joel-compiled/initramfs.cpio.gz"),
      repoRoot.resolve("spikes/linux-wasm/build/joel-demo/initramfs.cpio.gz")
    ).orElseThrow(() ->
      new IllegalStateException(
        "Linux WASM benchmark initrd is missing. Run `make -C spikes/linux-wasm fetch-joel-demo` " +
        "or configure linux.wasm.bench.initrd / LINUX_WASM_BENCH_INITRD."
      )
    );
  }

  private static long configuredTimeoutSeconds() {
    String configured = configuredValue(
      "linux.wasm.bench.timeoutSeconds",
      "LINUX_WASM_BENCH_TIMEOUT_SECONDS"
    );
    if (configured == null) {
      return 45L;
    }
    return Long.parseLong(configured);
  }

  private static String configuredValue(String property, String environmentVariable) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(environmentVariable);
    }
    return value == null || value.isBlank() ? null : value;
  }

  private static String[] forkedJvmProperties() {
    List<String> properties = new ArrayList<>();
    addForkedJvmProperty(properties, "linux.wasm.bench.root");
    addForkedJvmProperty(properties, "linux.wasm.bench.wasm");
    addForkedJvmProperty(properties, "linux.wasm.bench.initrd");
    addForkedJvmProperty(properties, "linux.wasm.bench.timeoutSeconds");
    return properties.toArray(String[]::new);
  }

  private static void addForkedJvmProperty(List<String> properties, String property) {
    String value = System.getProperty(property);
    if (value != null && !value.isBlank()) {
      properties.add("-D" + property + "=" + value);
    }
  }

  private static Optional<Path> firstExisting(Path... candidates) {
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static Path requireFile(Path path, String property) {
    Path normalized = path.toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized)) {
      throw new IllegalStateException(
        "Configured " + property + " does not exist or is not a regular file: " + normalized
      );
    }
    return normalized;
  }

  private static String tail(String text) {
    int maxChars = 16_000;
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(text.length() - maxChars);
  }

  private static String javaExecutableName() {
    return System.getProperty("os.name").toLowerCase().contains("win")
      ? "java.exe"
      : "java";
  }
}
