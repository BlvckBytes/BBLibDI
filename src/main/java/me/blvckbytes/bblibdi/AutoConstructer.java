package me.blvckbytes.bblibdi;

import me.blvckbytes.bblibutil.Tuple;
import me.blvckbytes.bblibutil.logger.ILogger;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 04/22/2022

  Scans all children of a package for classes which are annotated by the
  target annotation to be auto-constructed. Those classes may have multiple
  dependencies within their constructor, so the constructer generates a
  dependency graph and resolves it through creating one dependency after the other.
  Dependencies can be concrete classes or interfaces, where in the later case
  only one class is allowed to implement this interface within the package, an
  exception will be generated otherwise. In order to break up circular
  dependencies, injected dependencies can be marked as lateinit. A lateinit
  parameter always needs to have a corresponding class member field of the
  same type, into which the value gets injected as soon as it's available,
  where the constructor will be passed a null-value for it in the mean time.

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU Affero General Public License as published
  by the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Affero General Public License for more details.

  You should have received a copy of the GNU Affero General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
public class AutoConstructer implements IAutoConstructer {

  // Cache for already constructed classes (singletons)
  private final List<ConstructedRef> refs;

  // Cache for @AutoConstruct'ed class fields that are waiting for late init injections
  private final Map<Class<?>, List<Tuple<Object, Field>>> lateinits;

  private List<Class<?>> classes;
  private final JavaPlugin plugin;

  public AutoConstructer(JavaPlugin plugin) throws Exception {
    this.plugin = plugin;

    this.refs = new LinkedList<>();
    this.lateinits = new HashMap<>();
    this.classes = new ArrayList<>();

    this.execute();
  }

  //=========================================================================//
  //                               Consumer API                              //
  //=========================================================================//

  @Override
  public Collection<Object> getAllInstances() {
    return this.refs.stream()
      .map(ConstructedRef::getInst)
      .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Collection<Class<?>> getClasses() {
    return Collections.unmodifiableCollection(this.classes);
  }

  //=========================================================================//
  //                                Utilities                                //
  //=========================================================================//

  /**
   * Calls the cleanup-routine on all resources that implement
   * it in the exact reverse order of creation
   */
  public void cleanup() {
    // Iterate all created instances
    for (int i = refs.size() - 1; i >= 0; i--) {
      Object ref = refs.remove(i).getInst();

      // Does not implement the autoconstructed interface (which is not mandatory), thus skip
      if (!IAutoConstructed.class.isAssignableFrom(ref.getClass()))
        continue;

      // Call cleanup
      ((IAutoConstructed) ref).cleanup();
    }
  }

  /**
   * Find all classes within the provided package
   * @param pkg Package to search for targets in
   */
  private List<Class<?>> findAllClasses(String pkg) {
    List<Class<?>> classes = new ArrayList<>();

    try {
      // Get the executing jar's file path
      String fpath = new File(
        plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()
      ).getPath();

      // Transform the default package notation into a path
      String pathPkg = pkg.replace(".", "/");

      // Load the jar file by it's path
      JarFile jf = new JarFile(fpath);

      // Loop all of it's entries (packages, classes, files, ...)
      jf.entries().asIterator().forEachRemaining(je -> {
        String name = je.getName();

        // Not a class within the target package
        if (!(name.startsWith(pathPkg) && name.endsWith(".class")))
          return;

        // Try loading the class (should succeed every time) and add it to the local list
        try {
          classes.add(Class.forName(
            // Transform the path back into package notation and strip off .class
            name.substring(0, name.lastIndexOf('.')).replace("/", ".")
          ));
        } catch (Exception e) {
          e.printStackTrace();
        }
      });

      jf.close();
    } catch (Exception e) {
      e.printStackTrace();
    }

    return classes;
  }

  /**
   * Execute the auto-constructor and thus instantiate all available
   * classes within the plugin's package that are annotated by @AutoConstruct
   *
   * @throws Exception Errors during instantiation of modules
   */
  private void execute() throws Exception {
    this.classes = findAllClasses(plugin.getClass().getPackageName());

    // Mapping classes to a chosen constructor, which either has no deps or only @AutoInject dep parameters
    Map<Class<?>, Constructor<?>> ctorMap = selectConstructors(
      // Only return classes that have the matching annotation applied
      this.classes.stream()
        .filter(c -> c.isAnnotationPresent(AutoConstruct.class))
        .collect(Collectors.toList())
    );

    // Resolve all dependencies recursively
    List<Class<?>> seen = new ArrayList<>();
    for (Map.Entry<Class<?>, Constructor<?>> e : ctorMap.entrySet())
      createWithDependencies(ctorMap, e.getKey(), seen);

    // Lateinit resources should never remain null at this point
    if (!lateinits.isEmpty())
      throw new IllegalStateException("Could not inject all lateinit resources!");

    // Call the init method on all resources
    for (ConstructedRef r : refs) {
      if (r.getInst() instanceof IAutoConstructed)
        ((IAutoConstructed) r.getInst()).initialize();
    }

    ILogger logger = findLogger();
    if (logger != null)
      logger.logInfo("All resources have been instantiated");
  }

  /**
   * If the target is an interface, this routine tries to resolve it to an actual
   * implementing class to be worked with further on. Multiple or no implementations
   * will result in a RuntimeError
   *
   * @param target  Target class to resolve
   * @param classes List of available classes to choose from
   * @return Resolved class for interfaces, target for implementations
   */
  private Class<?> resolveInterface(Class<?> target, List<? extends Class<?>> classes) {
    // Not an interface, just return the implementation itself
    if (!target.isInterface())
      return target;

    // This dependency is an interface, search for a class that implements it
    Class<?> impl = null;

    // Search through all classes
    for (Class<?> pi : classes) {
      // Skip self
      if (pi == target)
        continue;

      // Not implementing this interface
      if (!target.isAssignableFrom(pi))
        continue;

      // There should never be multiple implementations present (for safety)
      // To disable another implementation, just remove it's @AutoConstruct annotation
      if (impl != null)
        throw new RuntimeException("Multiple Implementations for @AutoInject found: " + target.getName());

      // Use the first occurring implementation
      impl = pi;
    }

    // This interface never got implemented (or just not @AutoConstruct'ed)
    if (impl == null)
      throw new RuntimeException("No @AutoInject implementation found: " + target.getName());

    // Now continue with the actual implementation
    return impl;
  }

  /**
   * Transform a list of available classes into a map which assigns them to their
   * selected and valid constructor to be used later down the road. If a class has no
   * usable constructor, a RuntimeError will be thrown
   *
   * @param classes List of available classes
   * @return Map of class to valid constructor
   */
  private Map<Class<?>, Constructor<?>> selectConstructors(List<? extends Class<?>> classes) {
    Map<Class<?>, Constructor<?>> ctorMap = new HashMap<>();

    // Loop all classes
    for (Class<?> c : classes) {

      // Loop all constructors of this class
      Constructor<?> tarCtor = null;
      for (Constructor<?> ctor : c.getDeclaredConstructors()) {

        // Constructor is private
        // They need to be skipped as it would otherwise be hard to distinguish between
        // use intended and non-external-use intended constructors
        if (!ctor.canAccess(null))
          continue;

        // Whether or not this constructor is usable, which either means
        // that it only has @AutoInject parameters, or no parameters
        boolean isUsable = true;

        // Loop all parameters of this constructor
        for (Parameter p : ctor.getParameters()) {
          // Parameter without auto-inject annotation, cannot be resolved
          if (!p.isAnnotationPresent(AutoInject.class)) {
            isUsable = false;
            break;
          }
        }

        // Skip
        if (!isUsable)
          continue;

        // Use this constructor
        tarCtor = ctor;
        break;
      }

      // Cannot construct this class
      if (tarCtor == null)
        throw new RuntimeException("No valid @AutoConstruct constructors in " + c.getName());

      // Register this constructor
      ctorMap.put(c, tarCtor);
    }

    return ctorMap;
  }

  /**
   * Called whenever a resource has been instantiated
   * @param instance Created object
   * @param vanillaC Vanilla class (unresolved interface for example) of this object
   */
  private void onInstantiation(
    Object instance,
    Class<?> vanillaC
  ) throws Exception {
    // Keep a list of fields within this class that need to be late-initialized
    List<Field> lateinitFields = new ArrayList<>();

    // Walk this class and all of it's superclasses
    Class<?> c = instance.getClass();
    while (c.getSuperclass() != null) {
      for (Field f : c.getDeclaredFields()) {
        AutoInjectLate late = f.getAnnotation(AutoInjectLate.class);

        // Not a lateinit receiver
        if (late == null)
          continue;

        f.setAccessible(true);

        // Check if the dependency already exists
        boolean existed = false;
        for (ConstructedRef cr : refs) {
          if (f.getType().isAssignableFrom(cr.getType())) {
            f.set(instance, cr.getInst());
            existed = true;
            break;
          }
        }

        // Add to buffer
        if (!existed)
          lateinitFields.add(f);
      }

      c = c.getSuperclass();
    }

    // Add all fields as a tuple with their instance
    for (Field f : lateinitFields) {
      Class<?> t = f.getType();

      // Class not yet requested, create empty list
      if (!lateinits.containsKey(t))
        lateinits.put(t, new ArrayList<>());

      // Add the newly created object's lateinit request
      lateinits.get(t).add(new Tuple<>(instance, f));
    }

    // Check for lateinits that need this just created type
    for (Iterator<Map.Entry<Class<?>, List<Tuple<Object, Field>>>> li = lateinits.entrySet().iterator(); li.hasNext();) {
      Map.Entry<Class<?>, List<Tuple<Object, Field>>> entry = li.next();
      Class<?> lateinitC = entry.getKey();
      if (lateinitC.isAssignableFrom(vanillaC)) {
        li.remove();
        try {
          // Loop all tuples of object to field and set the value
          for (Tuple<Object, Field> fr : entry.getValue()) {
            Field f = fr.getB();
            f.setAccessible(true);
            f.set(fr.getA(), instance);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    // Also register events if the listener interface has been implemented
    if (instance instanceof Listener)
      plugin.getServer().getPluginManager().registerEvents((Listener) instance, plugin);
  }

  /**
   * Create a new instance of a class by creating all it's constructor's dependencies beforehand
   * @param ctorMap Constructor map of pre-selected, valid constructors
   * @param target Target class to construct
   * @param seen List of already seen classes, passed for recursion
   * @return Instantiated object ref
   *
   * @throws Exception Issues with instantiation or dependency conflicts
   */
  private Object createWithDependencies(
    Map<Class<?>, Constructor<?>> ctorMap,
    Class<?> target,
    List<Class<?>> seen
  ) throws Exception {
    // Resolve the interface implementation, if applicable
    Class<?> vanillaC = target;
    target = resolveInterface(target, new ArrayList<>(ctorMap.keySet()));

    // Already exists, return "singleton" object
    Class<?> finalTarget = target;
    Object existing = refs.stream()
      .filter(cr -> cr.getType().equals(finalTarget))
      .map(ConstructedRef::getInst)
      .findFirst()
      .orElse(null);

    if (existing != null)
      return existing;

    // Check for required plugin dependencies
    AutoConstruct ac = target.getAnnotation(AutoConstruct.class);
    for (String pluginDep : ac.pluginDependencies()) {
      if (Bukkit.getPluginManager().getPlugin(pluginDep) == null)
        throw new RuntimeException("@AutoConstruct plugin dependency not loaded: " + pluginDep);
    }

    // Check for existing constructor
    Constructor<?> targetC = ctorMap.get(target);
    if (targetC == null)
      throw new RuntimeException("Could not find @AutoInject dependency: " + target.getName());

    // Encountered a leaf node - no more dependencies to resolve
    Parameter[] params = targetC.getParameters();
    if (params.length == 0) {
      // Invoke empty constructor
      Object inst = targetC.newInstance();
      onInstantiation(inst, vanillaC);

      // Don't add temporaries
      if (!ac.temporary())
        refs.add(new ConstructedRef(target, inst));

      // As this dependency now exists, remove it from the seen list, as it
      // cannot cause any further circular dependencies
      seen.remove(target);
      return inst;
    }

    // Loop all parameters of this constructor and thus all dependencies
    Object[] args = new Object[params.length];
    for (int i = 0; i < params.length; i++) {
      Parameter p = params[i];
      Class<?> dep = p.getType();

      // Get the auto-inject annotation to access it's parameters
      AutoInject aia = p.getAnnotation(AutoInject.class);
      if (aia == null)
        throw new RuntimeException(
          "Parameter " + p.getName() + " of " + target.getName() + " is not annotated by @AutoInject"
        );

      // Directly inject the JavaPlugin ref
      if (dep.isAssignableFrom(plugin.getClass())) {
        args[i] = plugin;
        continue;
      }

      // Directly inject the AutoConstructor ref
      if (dep.isAssignableFrom(getClass())) {
        args[i] = this;
        continue;
      }

      // Circular dependency (not resolved but already seen)
      if (seen.contains(dep))
        throw new RuntimeException(
          "Circular @AutoInject dependency encountered: " + dep.getName() + " of " + target.getName()
        );

      // Remember this dependency and resolve it's dependencies
      seen.add(dep);
      args[i] = createWithDependencies(ctorMap, dep, seen);
      seen.remove(dep);
    }

    // Make sure that all type dependencies are loaded before calling the constructor
    for (Class<?> type : ac.typeDependencies()) {
      for (Class<?> knownType : ctorMap.keySet()) {
        if (type.isAssignableFrom(knownType))
          createWithDependencies(ctorMap, knownType, seen);
      }
    }

    // All constructor dependencies instantiated, now create the target itself
    // using all created dependencies

    Object inst;
    try {
      inst = targetC.newInstance(args);
    } catch (Exception e) {
      // Unwrap the inner exception
      throw new RuntimeException(e.getCause());
    }

    onInstantiation(inst, vanillaC);

    // Don't add temporaries
    if (!ac.temporary())
      refs.add(new ConstructedRef(target, inst));

    return inst;
  }

  /**
   * Find the logger within the local list of refs
   * @return Logger instance or null if it's not yet constructed
   */
  private ILogger findLogger() {
    // Look through ref-list
    for (ConstructedRef cr : refs) {
      // This is a logger implementation
      if (ILogger.class.isAssignableFrom(cr.getType()))
        return (ILogger) cr.getInst();
    }

    // Logger not yet available
    return null;
  }
}