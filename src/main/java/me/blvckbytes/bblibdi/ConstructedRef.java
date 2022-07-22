package me.blvckbytes.bblibdi;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 07/17/2022

  Represents an auto-constructed class reference.
*/
@Getter
@AllArgsConstructor
public class ConstructedRef {
  private Class<?> type;
  private Object inst;
}
