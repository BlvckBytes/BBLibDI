package me.blvckbytes.bblibdi;

/*
  Author: BlvckBytes <blvckbytes@gmail.com>
  Created On: 04/23/2022

  Represents an @AutoConstruct'ed resource which provides a lifecycle API.
*/
public interface IAutoConstructed {

  /**
   * Called to clean up before the instance is about to be destroyed
   */
  void cleanup();

  /**
   * Called to initialize after all resources have been constructed
   */
  void initialize();
}
