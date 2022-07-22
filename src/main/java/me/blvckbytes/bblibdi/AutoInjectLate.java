package me.blvckbytes.bblibdi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 04/29/2022

  Marks a class' field to be a @AutoConstruct dependency which is
  injected later (as soon as it becomes available).
*/
@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoInjectLate {}
