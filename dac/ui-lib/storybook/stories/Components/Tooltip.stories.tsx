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

import { Meta, StoryFn } from "@storybook/react";

import { Tooltip } from "../../../components";

export default {
  title: "Components/Tooltip",
  component: Tooltip,
} as Meta<typeof Tooltip>;

export const Default: StoryFn<typeof Tooltip> = () => {
  return (
    <Tooltip
      content={
        <p className="dremio-prose">
          <strong>Lorem ipsum dolor sit amet,</strong> consectetur adipiscing
          elit. Nam neque ante, porttitor vel convallis in, ullamcorper sed
          arcu. In ultrices magna nec auctor feugiat. Quisque aliquam, nulla et
          scelerisque condimentum, magna quam condimentum erat, non ultrices est
          arcu in lorem. Vivamus nec mi auctor, ornare dolor vitae, feugiat mi.
          Nam sodales metus sed tortor iaculis, quis convallis tellus ornare.
          Phasellus ac faucibus arcu. Suspendisse nec ipsum augue. Nullam tempus
          tellus a enim luctus luctus. Vestibulum eu nibh et velit varius
          tincidunt quis vitae lectus. Cras cursus turpis arcu, quis facilisis
          sem eleifend ac. Suspendisse aliquet, lacus eu auctor pellentesque,
          lorem odio venenatis tortor, quis mollis libero ipsum vitae massa. Sed
          ullamcorper imperdiet felis, id dignissim nunc elementum ut.
          Pellentesque tincidunt felis vitae pulvinar varius. Nunc a erat congue
          orci tristique malesuada. Nullam dictum facilisis pretium. Duis ligula
          mauris, aliquam ut tortor elementum, euismod euismod mi.
        </p>
      }
      interactive
    >
      <span>Hover me</span>
    </Tooltip>
  );
};

Default.storyName = "Tooltip";
