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
package com.dremio.exec.testing;

import com.dremio.common.concurrent.ExtendedLatch;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Injection for a single pause. Pause indefinitely until signalled. This class is used internally
 * for tracking injected pauses. Note that pauses can be fired only once; nFire field is ignored.
 * These pauses are specified via {@link com.dremio.exec.ExecConstants#NODE_CONTROL_INJECTIONS}
 * session option.
 *
 * <p>After the pauses are set, the user sends another signal to unpause all the pauses. This
 * triggers the AttemptManager to 1) unpause all pauses in QueryContext, and 2) send an unpause
 * signal to all fragments, each of which unpauses all pauses in FragmentContext.
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class PauseInjection extends Injection {

  private final ExtendedLatch latch = new ExtendedLatch(1);

  @JsonCreator // ensures instances are created only through JSON
  private PauseInjection(
      @JsonProperty("address") final String address,
      @JsonProperty("port") final int port,
      @JsonProperty("siteClass") final String siteClass,
      @JsonProperty("desc") final String desc,
      @JsonProperty("nSkip") final int nSkip)
      throws InjectionConfigurationException {
    super(address, port, siteClass, desc, nSkip, 1);
  }

  public void pause() {
    if (!injectNow()) {
      return;
    }
    latch.awaitUninterruptibly();
  }

  public void interruptiblePause() throws InterruptedException {
    if (!injectNow()) {
      return;
    }
    latch.await();
  }

  public void unpause() {
    latch.countDown();
  }
}
