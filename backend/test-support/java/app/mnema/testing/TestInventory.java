package app.mnema.testing;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeSet;

/** Discovers, but never executes, every compiled candidate; names such as *EvalRunner are included. */
public final class TestInventory {
    private TestInventory() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        var selectors = new ArrayList<DiscoverySelector>();
        for (int index = 1; index < args.length; index++) {
            Path root = Path.of(args[index]);
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".class"))
                        .map(path -> root.relativize(path).toString().replace(java.io.File.separatorChar, '.'))
                        .filter(name -> !name.contains("$") && !name.equals("module-info.class"))
                        .map(name -> name.substring(0, name.length() - ".class".length()))
                        .sorted().map(DiscoverySelectors::selectClass).forEach(selectors::add);
            }
        }
        var names = new TreeSet<String>();
        try (var session = LauncherFactory.openSession()) {
            var plan = session.getLauncher().discover(LauncherDiscoveryRequestBuilder.request().selectors(selectors).build());
            for (var root : plan.getRoots()) {
                for (var identifier : plan.getDescendants(root)) {
                    if (identifier.getSource().orElse(null) instanceof ClassSource source) {
                        names.add(source.getClassName().split("\\$", 2)[0]);
                    }
                }
            }
        }
        if (names.isEmpty()) throw new IllegalStateException("No discovered test classes");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writeValueAsString(names) + "\n");
        Files.write(output.resolveSibling("test-inventory.txt"), names);
        System.out.println("Discovered " + names.size() + " test classes without executing tests");
    }
}
