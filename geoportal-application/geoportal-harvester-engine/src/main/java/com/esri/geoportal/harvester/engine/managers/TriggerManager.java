/*
 * Copyright 2016 Esri, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.geoportal.harvester.engine.managers;

import com.esri.geoportal.harvester.api.Trigger;
import com.esri.geoportal.harvester.api.defs.TriggerInstanceDefinition;
import com.esri.geoportal.harvester.engine.support.CrudsRepo;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger manager.
 */
public interface TriggerManager extends CrudsRepo<TriggerInstanceDefinition> {
  
  /**
   * Gets all active trigger instances.
   * @return list of all active trigger instances
   */
  Map<UUID,Trigger.Instance>  getInstances();
  
  /**
   * Removes instance from the map.
   * @param instance instance to remove
   */
  void removeInstance(Trigger.Instance instance);
}