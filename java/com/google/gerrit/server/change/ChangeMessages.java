// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.change;

import org.eclipse.jgit.nls.NLS;
import org.eclipse.jgit.nls.TranslationBundle;

public class ChangeMessages extends TranslationBundle {
  public static ChangeMessages get() {
    return NLS.getBundleFor(ChangeMessages.class);
  }

  public String revertChangeDefaultMessage;

  public String reviewerCantSeeChange;
  public String reviewerInvalid;
  public String reviewerNotFoundUserOrGroup;

  public String groupIsNotAllowed;
  public String groupHasTooManyMembers;
  public String groupManyMembersConfirmation;
}