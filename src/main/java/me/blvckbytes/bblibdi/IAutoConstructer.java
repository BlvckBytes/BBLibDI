package me.blvckbytes.bblibdi;

import java.util.Collection;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 05/04/2022

  Public interfaces which the auto-constructer provides to other consumers.
*/
public interface IAutoConstructer {

  /**
   * Get all instantiated {@link AutoConstruct} resources
   */
  Collection<Object> getAllInstances();
}
