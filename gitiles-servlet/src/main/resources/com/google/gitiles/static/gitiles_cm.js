/**
 * @license
 * Copyright 2014 Google Inc. All Rights Reserved.
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

/**
 * Initialize a CodeMirror instance.
 *
 * @param {string} id id of a textarea instance.
 * @param {Object|undefined} opt_opts optional option dict to pass to
 *     CodeMirror.
 * @return {CodeMirror} CodeMirror instance.
 */
function initCodeMirror(textareaId, opt_opts) {
  document.body.style.overflow = "hidden";

  var opts = opt_opts || {};
  var defaults = {
    lineNumbers: true,
    readOnly: true
  };
  for (var prop in defaults) {
    if (!opts.hasOwnProperty(prop)) {
      opts[prop] = defaults[prop];
    }
  }
  var cm = CodeMirror.fromTextArea(document.getElementById(textareaId), opts);

  setCmSize(cm);

  var resize = function(e) {
    setCmSize(cm);
  };
  if (window.addEventListener) {
    window.addEventListener("resize", resize);
  } else {
    window.attachEvent("onresize", resize);
  }
  return cm;
}

/**
 * Set the size of a CodeMirror instance to take up the whole viewport.
 *
 * @param {CodeMirror} cm CodeMirror instance.
 */
function setCmSize(cm) {
  var winHeight = window.innerHeight
       || document.documentElement.clientHeight
       || document.body.clientHeight;
  var cmTop = cm.getWrapperElement().getBoundingClientRect().top;
  cm.setSize("100%", winHeight - cmTop - 23);
}
