package me.blvckbytes.bblibdi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 04/22/2022

  Marks a class to be intended for use with AutoConstructer.
*/
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoConstruct {

  /**
   * Specify any plugin dependencies this module requires here,
   * and the {@link AutoConstructer} will exit and quit if one of
   * them is missing on instantiation
   */
  String[] pluginDependencies() default {};

  /**
   * Specify any types of classes here, that this module is
   * depending on at runtime, after instantiation
   */
  Class<?>[] typeDependencies() default {};
}
