/* ══════════════════════════════════════════════════════════════════
   7kimisu 模块网页桥
   ──────────────────────────────────────────────────────────────────
   模块自带的 WebUI 是跑在本页面的 <iframe> 里的，它期望有一个 window.ksu
   对象（KernelSU 的 JS API，见仓库 js/index.js）。这个文件就是把它补上，
   对齐的是管理器 App 里 WebView 桥的语义（cwd = 模块目录、带 KSU_MODULE 等）。

   服务端在页面里注入了两个变量：
     window.__7K_BASE__   = 网页管理器的路径前缀
     window.__7K_MODULE__ = 这个模块的信息（module.prop 的字段 + moduleDir）

   支持：exec / spawn / toast / moduleInfo / listPackages / getPackagesInfo /
        fullScreen / enableEdgeToEdge / exit
   `exec` / `spawn` 的 options 支持 `cwd` 与 `env`（工作目录与环境变量注入）——我们是直接设进程的
   工作目录和环境变量，比 App 把 `cd x; export K=V;` 拼进命令行的做法更稳（路径里有空格/分号也不会被拆）。
   已知差异：
     · **spawn 的 stdin 不支持**（KernelSU 的 JS 里 `child.stdin` 只有事件收发、没有 write 入口，
       管理器 App 侧也没接）——模块若往里 emit，这里会**明确告警**而不是静默；
     · `getPackagesInfo` 的 appLabel 用包名兜底（读 APK 资源里的应用名需要 Android 框架）。

   ⚠️ `exec` 有**两种写法**，管理器 App 里都支持，这里也必须都支持：
     ① 回调式（官方 js/index.js 用的）：ksu.exec(cmd, optionsJson, callbackName)
     ② 同步拿返回值（很多模块自己写的老办法）：const out = ksu.exec(cmd)
        —— 浏览器里只有"同步 XHR"能做到调完就有返回值；这会阻塞界面线程，
           但模块就是这么写的，而且管理器 App 里同样是同步的（行为一致）。
   ══════════════════════════════════════════════════════════════════ */
(function () {
  "use strict";
  if (window.ksu) return;

  var BASE = window.__7K_BASE__ || "";
  var MOD = window.__7K_MODULE__ || { id: "" };

  function qs(params) {
    return Object.keys(params).map(function (k) {
      return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]);
    }).join("&");
  }

  function post(api, params) {
    return fetch(BASE + api, {
      method: "POST",
      cache: "no-store",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: qs(params)
    }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  /* 同步请求：给"直接拿返回值"的 exec 用（浏览器里只有这个办法能同步拿到结果） */
  function postSync(api, params) {
    try {
      var xhr = new XMLHttpRequest();
      xhr.open("POST", BASE + api, false);
      xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
      xhr.send(qs(params));
      if (xhr.status !== 200) return null;
      return JSON.parse(xhr.responseText);
    } catch (e) {
      console.warn("[7kimisu] 同步 exec 失败: " + e);
      return null;
    }
  }

  /* spawn：给那个 ChildProcess 对象发 error 事件（App 的桥也是这么做的） */
  function emitError(child, code, message) {
    if (!child || typeof child.emit !== "function") return;
    try {
      var err = new Error(message || "");
      err.exitCode = code;
      child.emit("error", err);
    } catch (e) {
      console.error(e);
    }
  }

  function callBack(name, a, b, c) {
    var fn = window[name];
    if (typeof fn === "function") {
      try { fn(a, b, c); } catch (e) { console.error(e); }
    }
  }

  var impl = {
    /* 两种写法都支持：见文件头说明 */
    exec: function (cmd, options, callbackName) {
      var optsJson = (typeof options === "string" && options) ? options : "{}";
      if (typeof callbackName === "string" && callbackName) {
        // ① 回调式
        post("/api/module/exec", { id: MOD.id, cmd: String(cmd), options: optsJson })
          .then(function (r) {
            callBack(callbackName, r.errno | 0, r.stdout || "", r.stderr || "");
          })
          .catch(function (e) {
            callBack(callbackName, 1, "", String(e && e.message ? e.message : e));
          });
        return;
      }
      // ② 同步拿返回值（管理器 App 里返回的就是命令的 stdout 字符串）
      var r = postSync("/api/module/exec", { id: MOD.id, cmd: String(cmd) });
      if (!r || !r.ok) {
        return "";
      }
      return r.stdout === undefined ? "" : String(r.stdout);
    },

    /* ksu.spawn(command, argsJson, optionsJson, callbackName)
       → 往 window[callbackName]（ChildProcess）上发 stdout/stderr 的 data 事件和 exit
       实现：ksud 那边起真进程，这里每 250ms 轮询一次增量，按行 emit（与 App 的桥一致） */
    spawn: function (command, argsJson, optionsJson, callbackName) {
      var child = window[callbackName];
      if (!child) {
        console.warn("[7kimisu] spawn 找不到回调对象 " + callbackName);
        return;
      }
      var args = [];
      try { args = JSON.parse(argsJson || "[]"); } catch (e) { args = []; }
      var optsJson = (typeof optionsJson === "string" && optionsJson) ? optionsJson : "{}";

      /* stdin：KernelSU 的 JS 里 child.stdin 只有事件收发、没有 write 入口，管理器 App 也没接。
         所以这里给个明确提示，别让模块以为"写进去了" */
      if (child.stdin && typeof child.stdin.emit === "function" && !child.stdin.__7kWrapped) {
        var rawEmit = child.stdin.emit.bind(child.stdin);
        child.stdin.__7kWrapped = true;
        child.stdin.emit = function (ev) {
          if (ev === "data" || ev === "end") {
            console.warn("[7kimisu] 网页版不支持向子进程写 stdin（管理器 App 也不支持）—— 这次写入被忽略了");
            return;
          }
          return rawEmit.apply(null, arguments);
        };
      }

      /* 把增量文本按行喂给事件；最后一段可能不完整，留到下一轮 */
      function emitLines(name, text) {
        if (!text) return;
        var key = "__7k_" + name;
        var buf = (child[key] || "") + text;
        var parts = buf.split("\n");
        child[key] = parts.pop();
        parts.forEach(function (line) {
          try { child[name].emit("data", line); } catch (e) { console.error(e); }
        });
      }

      post("/api/module/spawn", {
        id: MOD.id,
        cmd: String(command),
        args: JSON.stringify(args),
        options: optsJson
      }).then(function (r) {
        if (!r.ok) { emitError(child, 1, r.error || "启动失败"); return; }
        var sid = r.sid;
        (function tick() {
          post("/api/module/spawn/poll", { sid: sid }).then(function (p) {
            if (!p.ok) { emitError(child, 1, p.error || "会话失效"); return; }
            emitLines("stdout", p.stdout);
            emitLines("stderr", p.stderr);
            if (!p.done) { setTimeout(tick, 250); return; }
            // 收尾：把残留的半行也发出去，然后 exit / error
            ["stdout", "stderr"].forEach(function (n) {
              var key = "__7k_" + n;
              if (child[key]) {
                try { child[n].emit("data", child[key]); } catch (e) { console.error(e); }
                child[key] = "";
              }
            });
            var code = p.code | 0;
            if (typeof child.emit === "function") {
              try { child.emit("exit", code); } catch (e) { console.error(e); }
            }
            if (code !== 0) emitError(child, code, p.stderr || "");
          }).catch(function (e) {
            emitError(child, 1, String(e && e.message ? e.message : e));
          });
        })();
      }).catch(function (e) {
        emitError(child, 1, String(e && e.message ? e.message : e));
      });
    },

    /* 已安装的包名（同步接口，返回 JSON 字符串） */
    listPackages: function (type) {
      var r = postSync("/api/module/packages", { type: String(type || "") });
      if (!r || !r.ok) return "[]";
      return JSON.stringify(r.packages || []);
    },

    /* 一批包的详细信息（同步接口；appLabel 用包名兜底） */
    getPackagesInfo: function (namesJson) {
      var r = postSync("/api/module/packages-info", { names: String(namesJson || "[]") });
      if (!r || !r.ok) return "[]";
      return JSON.stringify(r.list || []);
    },

    /* 让父页面（网页管理器）弹提示 */
    toast: function (msg) {
      parent.postMessage({ source: "7kimisu-webui", type: "toast", message: String(msg) }, "*");
    },

    fullScreen: function () { /* 浏览器里本来就是全屏，忽略 */ },
    enableEdgeToEdge: function () { /* 同上 */ },

    /* 同步返回模块信息（服务端注入的，不用请求） */
    moduleInfo: function () {
      return JSON.stringify(MOD);
    },

    exit: function () {
      parent.postMessage({ source: "7kimisu-webui", type: "exit" }, "*");
    }
  };

  window.ksu = new Proxy(impl, {
    get: function (t, p) {
      if (p in t) return t[p];
      console.warn("[7kimisu] 模块调用网页版没有的 ksu." + String(p));
      return function () { return ""; };
    }
  });
})();
