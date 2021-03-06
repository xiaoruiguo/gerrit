/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import {html} from '@polymer/polymer/lib/utils/html-tag';

export const htmlTemplate = html`
  <style include="shared-styles">
    /* Workaround for empty style block - see https://github.com/Polymer/tools/issues/408 */
  </style>
  <style include="gr-form-styles">
    input:not([type='checkbox']),
    gr-autocomplete,
    iron-autogrow-textarea {
      width: 100%;
    }
    .value {
      width: 32em;
    }
    .hide {
      display: none;
    }
    @media only screen and (max-width: 40em) {
      .value {
        width: 29em;
      }
    }
  </style>
  <div class="gr-form-styles">
    <section class$="[[_computeBranchClass(baseChange)]]">
      <span class="title">Select branch for new change</span>
      <span class="value">
        <gr-autocomplete
          id="branchInput"
          text="{{branch}}"
          query="[[_query]]"
          placeholder="Destination branch"
        >
        </gr-autocomplete>
      </span>
    </section>
    <section class$="[[_computeBranchClass(baseChange)]]">
      <span class="title">Provide base commit sha1 for change</span>
      <span class="value">
        <iron-input
          maxlength="40"
          placeholder="(optional)"
          bind-value="{{baseCommit}}"
        >
          <input
            is="iron-input"
            id="baseCommitInput"
            maxlength="40"
            placeholder="(optional)"
            bind-value="{{baseCommit}}"
          />
        </iron-input>
      </span>
    </section>
    <section>
      <span class="title">Enter topic for new change</span>
      <span class="value">
        <iron-input
          maxlength="1024"
          placeholder="(optional)"
          bind-value="{{topic}}"
        >
          <input
            is="iron-input"
            id="tagNameInput"
            maxlength="1024"
            placeholder="(optional)"
            bind-value="{{topic}}"
          />
        </iron-input>
      </span>
    </section>
    <section id="description">
      <span class="title">Description</span>
      <span class="value">
        <iron-autogrow-textarea
          id="messageInput"
          class="message"
          autocomplete="on"
          rows="4"
          max-rows="15"
          bind-value="{{subject}}"
          placeholder="Insert the description of the change."
        >
        </iron-autogrow-textarea>
      </span>
    </section>
    <section class$="[[_computePrivateSectionClass(_privateChangesEnabled)]]">
      <label class="title" for="privateChangeCheckBox">Private change</label>
      <span class="value">
        <input
          type="checkbox"
          id="privateChangeCheckBox"
          checked$="[[_formatBooleanString(privateByDefault)]]"
        />
      </span>
    </section>
  </div>
  <gr-rest-api-interface id="restAPI"></gr-rest-api-interface>
`;
