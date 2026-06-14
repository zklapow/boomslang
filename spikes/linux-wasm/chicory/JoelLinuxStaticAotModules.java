import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import java.util.Map;
import java.util.function.Function;

final class JoelLinuxStaticAotModules {

  private JoelLinuxStaticAotModules() {}

  static Function<Instance, Machine> vmlinuxMachineFactory() {
    return null;
  }

  static Map<String, JoelLinuxLinkedUserModule> userModulesBySha256() {
    return Map.of();
  }
}
