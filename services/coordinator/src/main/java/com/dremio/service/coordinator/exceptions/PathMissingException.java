/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.coordinator.exceptions;

public class PathMissingException extends Exception {
  private static final long serialVersionUID = 1L;
  private static final String STR_FORMAT = "Parent path of %s does not exist";

  public PathMissingException(String path, Throwable th) {
    super(String.format(STR_FORMAT, path), th);
  }

  public PathMissingException(String path) {
    super(String.format(STR_FORMAT, path));
  }

  public PathMissingException(Throwable th) {
    super(th);
  }
}