// Copyright (C) 2009 Google Inc.
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

/**
 * @author maoziqing@gmail.com
 * @requires ___
 * @provides xhrModuleLoadMaker, scriptModuleLoadMaker, clearModuleCache
 *
 * Each load maker object, given a current module identifier and an
 * identifier resolver, returns a load object.
 *
 * A load object is a function object, load() returns the module object,
 * given the source URL.
 * load.async() returns a promise to the module, given the source URL.
 */
var xhrModuleLoadMaker;
var scriptModuleLoadMaker;
var clearModuleCache;

(function() {
  var cache = {};

  function addJsExtension(src) {
    if (src.toLowerCase().substring(src.length - 3) !== '.js') {
      src = src + ".js";
    }
    return src;
  }

  function defaultMidResolver(mid, src) {
    if (src[0] !== '.') {
      return src;
    }

    var k = mid.lastIndexOf("/");
    var newMid;
    if (k === -1) {
      newMid = src;
    } else {
      newMid = mid.substring(0, k + 1) + src;
    }

    while((k = newMid.indexOf("/./")) !== -1) {
      newMid = newMid.substring(0, k) + newMid.substring(k + 2);
    }

    while((k = newMid.indexOf("/../")) !== -1) {
      var p = newMid.lastIndexOf("/", k - 1);
      if (p === -1) {
        newMid = newMid.substring(k + 4);
      } else {
        newMid = newMid.substring(0, p) + newMid.substring(k + 3);
      }
    }

    return newMid;
  }

  function syncLoad(mid) {
    mid = addJsExtension(mid);
    if (cache[mid] === undefined || Q.near(cache[mid]).isPromise___) {
      throw new Error("The static module " + mid + " cannot be resolved.");
    }
    return Q.near(cache[mid]);
  }

  function loadMaker(mid, midResolver, asyncLoad) {
    var load = function(src) {
      return syncLoad(midResolver(mid, src));
    };
    load.FUNC___ = 'load';
    ___.setStatic(load, 'async', ___.markFuncFreeze(function (src) {
      return asyncLoad(midResolver(mid, src), midResolver);
    }));
    return ___.primFreeze(load);
  }

  function resolveDependency(module, load) {
    var r = Q.defer();
    if (module.includedModules !== undefined
        && module.includedModules.length !== 0) {
      var size = module.includedModules.length;
      var count = 0;
      for (var i = 0; i < size; i++) {
        var src = module.includedModules[i];
        var m = load.async(src);
        Q.when(m, function(childModule) {
                    count++;
                    if (count === size) {
                      r.resolve(true);
                    }
                  },
                  function(reason) {
                    r.resolve(Q.reject(
                        "Retrieving the module " + newMid + " failed."));
                  });
      }
    } else {
      r.resolve(true);
    }
    return r.promise;
  }

  function noop() {}

  function xhrAsyncLoad(mid, midResolver) {
    mid = addJsExtension(mid);
    if (cache[mid] !== undefined) {
      return cache[mid];
    }

    var r = Q.defer();
    var xhr = new XMLHttpRequest();  // TODO(mikesamuel): get working on IE
    xhr.onreadystatechange = function() {
      if (xhr.readyState === 4) {
        if (xhr.status === 200) {
          var savedModuleHandler = ___.getNewModuleHandler();
          ___.setNewModuleHandler(___.primFreeze({
            handle: ___.markFuncFreeze(function theHandler(module) {
              try {
                var load = loadMaker(mid, midResolver, xhrAsyncLoad);
                module.moduleId = mid;
                var securedModule = ___.prepareModule(module, load);
                cache[mid] = r.promise;
                var dependency = resolveDependency(module, load);
                Q.when(dependency, function(result) {
                                     r.resolve(securedModule);
                                   },
                                   function(reason) {
                                     r.resolve(Q.reject(
                                         "Resolving dependency for the"
                                         + "module " + mid + " failed."));
                                   });
              } catch (e) {
                r.resolve(Q.reject(e));
              }
            }),
            handleUncaughtException: savedModuleHandler.handleUncaughtException
          }));
          //TODO: validate the response before eval it
          eval(xhr.responseText);
          ___.setNewModuleHandler(savedModuleHandler);
          xhr.onreadystatechange = noop;  // avoid memory leak
        } else {
          r.resolve(Q.reject(
              "Retrieving the module " + mid + " failed, "
              + "status code = " + xhr.status));
        }
      }
    };

    xhr.open("GET", mid, true);
    xhr.overrideMimeType("application/javascript");
    xhr.send(null);
    return r.promise;
  }

  xhrModuleLoadMaker = ___.markFuncFreeze(function(mid, midResolver) {
    if (midResolver === undefined) {
      midResolver = defaultMidResolver;
    }

    return loadMaker(mid, midResolver, xhrAsyncLoad);
  });

  var head = 0;
  var queue = [];
  var busy = false;

  function scriptAsyncLoad(mid, midResolver) {
    mid = addJsExtension(mid);
    if (cache[mid] !== undefined) {
      return cache[mid];
    }

    var r = Q.defer();

    function dequeue() {
      if (head < queue.length) {
        busy = true;
        var savedHead = head;

        ___.setNewModuleHandler(___.primFreeze({
          handle: ___.markFuncFreeze(function theHandler(module) {
            if (savedHead === head) {
              var r = queue[head].defer;
              try {
                var curMid = queue[head].mid;
                var load = loadMaker(curMid, midResolver, scriptAsyncLoad);
                module.moduleId = mid;
                var securedModule = ___.prepareModule(module, load);
                cache[curMid] = r.promise;

                var dependency = resolveDependency(module, load);
                Q.when(dependency, function(result) {
                                     r.resolve(securedModule);
                                   },
                                   function(reason) {
                                     r.resolve(Q.reject(
                                         "Resolving dependency for the module "
                                         + curMid + " failed."));
                                   });
              } catch (e) {
                r.resolve(Q.reject(e));
              }
              delete queue[head];
              head++;
              dequeue();
            } else {
              // this should not happen
              // the module may have been mistakenly treated as a failure
            }
          })
        }));

        function timeout() {
          if (savedHead === head) {
            var r = queue[head].defer;
            r.resolve(Q.reject(
                "Retrieving the module " + queue[head].mid + " failed."));
            delete queue[head];
            head++;
            dequeue();
          } else {
            // the module has been loaded successfully
          }
        }

        var script = document.createElement("script");
        script.src = queue[head].mid;
        script.onerror = function() {
          timeout();
          script.onreadystatechange = script.onerror = null;
        };
        script.onreadystatechange = function() {
          if (script.readyState === 'loaded'
              || script.readyState === 'complete') {
            timeout();
            script.onreadystatechange = script.onerror = null;
          }
        };
        document.getElementsByTagName('head')[0].appendChild(script);
      } else {
            busy = false;
      }
    }

    var e = new Object();
    e.mid = mid;
    e.defer = r;
    queue.push(e);

    if (!busy) {
      dequeue();
    }

    return r.promise;
  }

  scriptModuleLoadMaker =  ___.markFuncFreeze(function(mid, midResolver) {
    if (midResolver === undefined) {
      midResolver = defaultMidResolver;
    }

    return loadMaker(mid, midResolver, scriptAsyncLoad);
  });

  clearModuleCache = ___.markFuncFreeze(function() {
    cajita.forOwnKeys(cache, ___.markFuncFreeze(function(k, v) {
      delete cache[k];
    }));
  });
})();