// Copyright (C) 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * @fileoverview
 * Loaded by domita_test.html and associated tests to verify that dynamic script
 * loading works.
 */

if (typeof ___ !== undefined) {
  (function () {
    var mh = ___.getNewModuleHandler();
    if (mh) {
      var imp = mh.getImports();
      if (imp && imp.externalScript) {
        imp.externalScript.loaded = true;
      }
    }
  })();
}
