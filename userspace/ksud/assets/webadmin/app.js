/* ══════════════════════════════════════════════════════════════════
   7kimisu 网页管理器 — 页面逻辑
   ──────────────────────────────────────────────────────────────────
   安全约定（改这个文件时别破坏）：
     · 所有动态数据（模块名/说明/App 名…）一律用 textContent 写入，
       绝不拼进 innerHTML —— 那些内容来自第三方 module.prop，属于不可信数据。
       本文件里的 innerHTML 只用于「我自己写的静态模板」。
     · 所有请求都只发往同源（127.0.0.1），页面没有任何外部地址。
   ══════════════════════════════════════════════════════════════════ */
"use strict";

// 🔐 访问鉴权（2026-09-18）：地址里必须带**专属密钥**（`{PATH}/{token}`）。
// 服务端把带密钥的完整前缀注入到下面的 P 里，所以本文件所有请求天然带密钥 ——
// 本文件里**不要**自己拼 /x7k9f 或任何不带密钥的地址。
var P = "__P__";          // 路径前缀（服务端注入，**含访问密钥**）
var NS = "http://www.w3.org/2000/svg";

var state = {
  tab: "status",
  modules: [],
  search: "",
  sort: "default",
  theme: "auto",
  busy: false,
  // 超级用户
  apps: [],
  appSearch: "",
  appFilter: "all",     // all | granted | plain | system
  // 超级用户日志
  sulog: [],
  sulogQuery: "",
  sulogEnabled: false,
  sulogSupported: true
};

/* ── DOM 小工具 ── */
function $(sel, root) { return (root || document).querySelector(sel); }
function el(tag, cls, text) {
  var n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;   // ⚠️ 只用 textContent，防 XSS
  return n;
}
function ic(name, cls) {
  var svg = document.createElementNS(NS, "svg");
  svg.setAttribute("class", cls || "ic");
  var use = document.createElementNS(NS, "use");
  use.setAttribute("href", "#i-" + name);
  svg.appendChild(use);
  return svg;
}
function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

/* ── 请求 ──
   ⚠️ 查询参数一律通过 params 传，**绝不要自己往 api 里塞 "?xxx"**。
   踩过的坑（v0.13.99 真机）：给 get() 传了 "/api/app/profile?pkg=x&uid=1"，
   而 get() 在末尾拼 "?k=口令"（当时还有口令）→ 变成 "...?pkg=x&uid=1?k=token"，
   uid 被当成 "1?k=token" 解析失败 → 「配置」按钮点了没反应、日志页也打不开。
   现在统一由 qs() 拼，结构上不可能再拼错。 */
function qs(params) {
  if (!params) return "";
  return Object.keys(params).filter(function (k) {
    return params[k] !== undefined && params[k] !== null;
  }).map(function (k) {
    return "&" + encodeURIComponent(k) + "=" + encodeURIComponent(params[k]);
  }).join("");
}
function apiUrl(api, params) {
  var extra = qs(params);            // 形如 "&pkg=x&uid=1"
  return P + api + (extra ? "?" + extra.slice(1) : "");
}
function get(api, params) {
  return fetch(apiUrl(api, params), { cache: "no-store" }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  });
}
function post(api, params) {
  var body = Object.keys(params).map(function (k) {
    return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]);
  }).join("&");
  return fetch(apiUrl(api), {
    method: "POST", cache: "no-store",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body
  }).then(function (r) {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  });
}

/* ── Toast ── */
var toastTimer = null;
function toast(msg) {
  var t = $("#toast");
  t.textContent = msg;
  t.classList.add("on");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { t.classList.remove("on"); }, 2400);
}

/* ── 底部弹层 ── */
function closeSheet() { $("#mask").classList.remove("on"); }
function openSheet(title, bodyNode, actions, full) {
  var sheet = $("#sheet");
  clear(sheet);
  sheet.classList.toggle("full", !!full);
  sheet.appendChild(el("div", "grip"));
  if (title) sheet.appendChild(el("h2", null, title));
  if (bodyNode) sheet.appendChild(bodyNode);
  if (actions && actions.length) {
    var btns = el("div", "btns");
    actions.forEach(function (a) {
      var b = el("button", "btn" + (a.cls ? " " + a.cls : ""), a.label);
      b.onclick = function () { if (a.keepOpen !== true) closeSheet(); a.onClick && a.onClick(); };
      btns.appendChild(b);
    });
    sheet.appendChild(btns);
  }
  $("#mask").classList.add("on");
}
function confirmSheet(title, body, okLabel, onOk) {
  openSheet(title, el("div", "body", body), [
    { label: "取消" },
    { label: okLabel, cls: "danger", onClick: onOk }
  ]);
}

/* 一排可以选中的过滤标签（筛选条件 / 条数） */
function chipsRow(container, items, current, onPick) {
  var row = el("div", "chips");
  items.forEach(function (c) {
    var b = el("button", "chip" + (current() === c.id ? " on" : ""), c.label);
    b.onclick = function () {
      var all = row.querySelectorAll(".chip");
      for (var i = 0; i < all.length; i++) all[i].classList.remove("on");
      b.classList.add("on");
      onPick(c.id);
    };
    row.appendChild(b);
  });
  container.appendChild(row);
  return row;
}

/* ── 主题 ── */
var THEMES = [
  { id: "auto",    name: "跟随系统",   sw: ["--bg", "--accent", "--ok"] },
  { id: "7k",      name: "7kimisu",    sw: ["--bg", "--accent", "--ok"] },
  { id: "mocha",   name: "Catppuccin Mocha", sw: ["--bg", "--accent", "--ok"] },
  { id: "latte",   name: "Catppuccin Latte", sw: ["--bg", "--accent", "--ok"] },
  { id: "nord",    name: "Nord",       sw: ["--bg", "--accent", "--ok"] },
  { id: "dracula", name: "Dracula",    sw: ["--bg", "--accent", "--ok"] }
];
var PROBE = { mocha: ["#1e1e2e", "#cba6f7", "#a6e3a1"], latte: ["#eff1f5", "#8839ef", "#40a02b"],
              nord: ["#2e3440", "#88c0d0", "#a3be8c"], dracula: ["#282a36", "#bd93f9", "#50fa7b"],
              "7k": ["#0b0f12", "#4c9aff", "#3ddc84"], auto: ["#0b0f12", "#4c9aff", "#3ddc84"] };

function applyTheme() {
  var id = state.theme;
  var html = document.documentElement;
  var scheme;
  if (id === "auto") {
    scheme = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    html.setAttribute("data-theme", "7k");
    html.setAttribute("data-scheme", scheme);
  } else {
    // latte 是浅色，其余是深色
    scheme = (id === "latte") ? "light" : "dark";
    html.setAttribute("data-theme", id);
    html.setAttribute("data-scheme", scheme);
  }
  try { localStorage.setItem("7k-webadmin-theme", id); } catch (e) {}
}

function themeSheet() {
  var box = el("div");
  THEMES.forEach(function (t) {
    var opt = el("button", "theme-opt" + (state.theme === t.id ? " on" : ""));
    var sw = el("div", "swatches");
    (PROBE[t.id] || PROBE["7k"]).forEach(function (c) {
      var i = el("i");
      i.style.background = c;
      sw.appendChild(i);
    });
    opt.appendChild(sw);
    opt.appendChild(el("div", "nm", t.name));
    if (state.theme === t.id) opt.appendChild(ic("check"));
    opt.onclick = function () { state.theme = t.id; applyTheme(); closeSheet(); toast("配色：" + t.name); };
    box.appendChild(opt);
  });
  openSheet("配色", box, [{ label: "关闭" }]);
}

/* ── 状态页 ── */
function badge(text, kind, iconName) {
  var b = el("span", "badge b-" + kind);
  if (iconName) b.appendChild(ic(iconName, "ic"));
  b.appendChild(el("span", null, text));
  return b;
}
function row(iconName, key, value, isBadge, kind) {
  var r = el("div", "row");
  var k = el("div", "k");
  if (iconName) k.appendChild(ic(iconName, "ic ic-sm"));
  k.appendChild(el("span", null, key));
  r.appendChild(k);
  if (isBadge) {
    var w = el("div", "v");
    w.appendChild(badge(value, kind || "dim"));
    r.appendChild(w);
  } else {
    r.appendChild(el("div", "v", value == null || value === "" ? "—" : String(value)));
  }
  return r;
}

/* 隐身模式：带开关的行（网页不受隐身影响，所以这里也能把隐身关掉） */
function stealthRow(s) {
  var r = el("div", "row");
  var k = el("div", "k");
  k.appendChild(ic("eye-off", "ic ic-sm"));
  k.appendChild(el("span", null, "隐身模式"));
  r.appendChild(k);
  r.appendChild(el("div", "sp"));

  var sw = el("div", "sw" + (s.stealth ? " on" : ""));
  sw.setAttribute("role", "switch");
  sw.title = s.stealth ? "点击关闭隐身" : "点击开启隐身";
  sw.onclick = function () {
    if (state.busy) return;
    var want = !s.stealth;
    if (want) {
      var code = s.stealthCode || "";
      var how = code ? "退出方式：拨号盘输入 *#*#" + code + "#*#*" : "退出方式：本页这个开关（拨号密令未读到）";
      confirmSheet("开启隐身模式？",
        "开启后管理器界面会伪装成「未安装」，超级用户 / 模块等页面全部隐藏，" +
        "桌面图标保持可见。\n\n" + how +
        "\n\n（放心：本网页不受隐身影响，万一拨号退不出来，回到这一页关掉即可）",
        "开启", function () { setStealth(true); });
    } else {
      confirmSheet("关闭隐身模式？", "关闭后管理器界面立即恢复正常显示。", "关闭",
        function () { setStealth(false); });
    }
  };
  r.appendChild(sw);
  return r;
}

function setStealth(enable) {
  state.busy = true;
  post("/api/stealth", { enable: enable ? "1" : "0" }).then(function (r) {
    toast(r.message || (r.ok ? "已切换" : (r.error || "操作失败")));
    setTimeout(loadStatus, 500);
  }).catch(function (e) { toast("操作失败：" + e.message); })
    .then(function () { state.busy = false; });
}

function loadStatus() {
  var box = $("#t-status");
  clear(box);
  for (var i = 0; i < 3; i++) box.appendChild(el("div", "skel"));
  get("/api/status").then(renderStatus).catch(function (e) {
    clear(box);
    var emp = el("div", "empty err");
    emp.appendChild(ic("triangle-alert"));
    emp.appendChild(el("div", null, "读取失败：" + e.message));
    box.appendChild(emp);
  });
}

function renderStatus(s) {
  var box = $("#t-status");
  clear(box);

  // 主卡：三种情况必须分开说，别把「隐身」和「没认主」混成一句
  //   root=true           → 已 root
  //   root=false + 隐身开 → 隐身中（root 其实正常，内核只是故意不认 App）
  //   root=false + 没隐身 → 内核还没认主（刷新/重开 App 就好）
  var hero = el("div", "card hero " + (s.root ? "on" : "off"));
  var ring = el("div", "ring");
  ring.appendChild(ic(s.root ? "shield-check" : (s.stealth ? "eye-off" : "shield-alert"), "ic"));
  hero.appendChild(ring);
  var txt = el("div");
  if (s.root) {
    txt.appendChild(el("div", "big", "已 root"));
    txt.appendChild(el("div", "small", (s.manager || "?") + " · KernelSU " + (s.ksu || 0)));
  } else if (s.stealth) {
    txt.appendChild(el("div", "big", "隐身模式已开启"));
    txt.appendChild(el("div", "small", "root 本身正常 —— 内核只是故意不告诉 App「我是管理器」"));
  } else {
    txt.appendChild(el("div", "big", "未识别到 root"));
    txt.appendChild(el("div", "small", "内核还没把本 App 认成管理器"));
  }
  hero.appendChild(txt);
  box.appendChild(hero);

  // 认不出来时给一条明确出路
  if (s.managerNotRecognized) {
    var tip = el("div", "card tight");
    var th = el("div", "card-title");
    th.appendChild(ic("info"));
    th.appendChild(el("span", null, "怎么恢复"));
    tip.appendChild(th);
    tip.appendChild(el("div", "hint",
      "这是「内核还没认主」，不是真的没 root：\n" +
      "· 先点右上角刷新一次；\n" +
      "· 还不行就把管理器 App 重新打开一次（新进程会重新拿到内核句柄），立刻恢复；\n" +
      "· 不用重装、不用重启手机、也不用重刷内核。"));
    box.appendChild(tip);
  }

  // 系统
  var c1 = el("div", "card tight");
  var t1 = el("div", "card-title");
  t1.appendChild(ic("cpu"));
  t1.appendChild(el("span", null, "系统"));
  c1.appendChild(t1);
  c1.appendChild(row("smartphone", "机型", s.model));
  c1.appendChild(row("cpu", "内核", s.kernel));
  c1.appendChild(row("layers", "KMI", s.kmi));
  c1.appendChild(row("hard-drive", "GKI", s.gki ? "是" : "否", true, "dim"));
  c1.appendChild(row("package", "LKM 模式", s.lkm ? "是" : "否", true, "dim"));
  box.appendChild(c1);

  // 防护状态
  var c2 = el("div", "card tight");
  var t2 = el("div", "card-title");
  t2.appendChild(ic("shield"));
  t2.appendChild(el("span", null, "当前状态"));
  c2.appendChild(t2);
  c2.appendChild(row("terminal", "root shell", s.rootShell ? "可用" : "不可用",
    true, s.rootShell ? "ok" : "bad"));
  c2.appendChild(row("lock", "安全模式", s.safeMode ? "已开启" : "未开启", true, s.safeMode ? "warn" : "ok"));
  c2.appendChild(stealthRow(s));
  box.appendChild(c2);

  // 服务端口（只读展示：用户 2026-09-17 拍板「照 DickSU 来，地址固定、什么都不要自定义」）
  var cPort = el("div", "card");
  var tPort = el("div", "card-title");
  tPort.appendChild(ic("hard-drive"));
  tPort.appendChild(el("span", null, "服务端口"));
  cPort.appendChild(tPort);
  cPort.appendChild(row("globe", "当前端口", String(s.port || 18427)));
  cPort.appendChild(el("div", "hint",
    "端口是固定的，不提供自定义。默认 18427；要是被别的程序占了，服务会自动往后顺延" +
    "（18437 / 18447 / 18457），上面这一行显示的永远是实际在用的那个。" +
    "想打开网页，用管理器 App 设置页里的地址最保险。"));
  box.appendChild(cPort);

  // 网页地址（照 DickSU 的做法：页面直接把地址显示出来，配一个「复制」按钮）
  var cAddr = el("div", "card");
  var tAddr = el("div", "card-title");
  tAddr.appendChild(ic("globe"));
  tAddr.appendChild(el("span", null, "网页地址"));
  cAddr.appendChild(tAddr);
  var fullUrl = "http://127.0.0.1:" + (s.port || 18427) + P;
  cAddr.appendChild(row("link", "地址", fullUrl));
  var aw = el("div", "row");
  aw.appendChild(el("div", "sp"));
  var copyBtn = el("button", "btn primary");
  copyBtn.appendChild(ic("copy", "ic"));
  copyBtn.appendChild(el("span", null, "复制地址"));
  copyBtn.onclick = function () { copyText(fullUrl, function () { toast("地址已复制"); }); };
  aw.appendChild(copyBtn);
  cAddr.appendChild(aw);
  cAddr.appendChild(el("div", "hint",
    "地址是固定的（本机才有），不用记、也不用改。手机上任何 App 都能访问这个地址 —— " +
    "不用的时候可以在管理器 App 的设置里把「网页管理器」关掉，关掉后端口就不再监听。"));
  box.appendChild(cAddr);

  // 隐身拨号密令（可自定义）
  var cCode = el("div", "card");
  var tCode = el("div", "card-title");
  tCode.appendChild(ic("dialpad"));
  tCode.appendChild(el("span", null, "隐身拨号密令"));
  cCode.appendChild(tCode);
  cCode.appendChild(row("eye-off", "当前密令", s.stealthCode || "（未读到）"));
  var editWrap = el("div", "row");
  var editK = el("div", "k");
  editK.appendChild(ic("key-round", "ic ic-sm"));
  editK.appendChild(el("span", null, "改成"));
  editWrap.appendChild(editK);
  var inp = el("input");
  inp.type = "tel";
  inp.inputMode = "numeric";
  inp.maxLength = 12;
  inp.placeholder = "4~12 位数字";
  inp.value = s.stealthCode || "";
  inp.className = "code-input";
  editWrap.appendChild(inp);
  var save = el("button", "btn primary");
  save.appendChild(el("span", null, "保存"));
  save.onclick = function () {
    if (state.busy) return;
    var code = (inp.value || "").replace(/[^0-9]/g, "").slice(0, 12);
    if (code.length < 4) { toast("密令至少 4 位数字"); return; }
    state.busy = true;
    post("/api/stealth-code", { code: code }).then(function (r) {
      toast(r.message || r.error || "已保存");
      setTimeout(loadStatus, 700);
    }).catch(function (e) { toast("保存失败：" + e.message); })
      .then(function () { state.busy = false; });
  };
  editWrap.appendChild(save);
  cCode.appendChild(editWrap);
  cCode.appendChild(el("div", "hint",
    "退出隐身：拨号盘输入 *#*#" + (s.stealthCode || "……") + "#*#*。" +
    "改完立刻生效（App 那边的密令显示会在下次打开时同步）。"));
  box.appendChild(cCode);

  // 统计
  var c3 = el("div", "card");
  var t3 = el("div", "card-title");
  t3.appendChild(ic("layout-dashboard"));
  t3.appendChild(el("span", null, "统计"));
  c3.appendChild(t3);
  var stats = el("div", "stats");
  [
    { n: s.moduleCount, l: "已装模块", i: "package" },
    { n: s.superuserCount, l: "已授权 App", i: "users" }
  ].forEach(function (x) {
    var st = el("div", "stat");
    st.appendChild(ic(x.i, "ic"));
    var d = el("div");
    d.appendChild(el("div", "n", String(x.n == null ? "—" : x.n)));
    d.appendChild(el("div", "l", x.l));
    st.appendChild(d);
    stats.appendChild(st);
  });
  c3.appendChild(stats);
  box.appendChild(c3);
}

/* ── 模块页 ── */
function loadModules() {
  var box = $("#modules");
  clear(box);
  for (var i = 0; i < 3; i++) box.appendChild(el("div", "skel"));
  get("/api/modules").then(function (list) {
    // ⚠️ ksud 给的模块标志是**字符串** "true"/"false"，JS 里 "false" 也是真值 ——
    //    不归一化的话：所有标签都会显示、开关状态还是反的、给没有动作脚本的模块也显示「动作」按钮。
    state.modules = (Array.isArray(list) ? list : []).map(normalizeModule);
    renderModules();
  }).catch(function (e) {
    clear(box);
    var emp = el("div", "empty err");
    emp.appendChild(ic("triangle-alert"));
    emp.appendChild(el("div", null, "读取失败：" + e.message));
    box.appendChild(emp);
  });
}

function filteredModules() {
  var list = state.modules.slice();
  var q = state.search.trim().toLowerCase();
  if (q) {
    list = list.filter(function (m) {
      return ((m.name || "") + " " + (m.id || "") + " " + (m.author || "") + " " + (m.description || ""))
        .toLowerCase().indexOf(q) >= 0;
    });
  }
  if (state.sort === "enabled") {
    list.sort(function (a, b) { return (b.enabled ? 1 : 0) - (a.enabled ? 1 : 0); });
  } else if (state.sort === "name") {
    list.sort(function (a, b) { return String(a.name || a.id).localeCompare(String(b.name || b.id), "zh"); });
  }
  return list;
}

function renderModules() {
  var box = $("#modules");
  clear(box);
  var list = filteredModules();
  if (!state.modules.length) {
    var emp = el("div", "empty");
    emp.appendChild(ic("package"));
    emp.appendChild(el("div", null, "还没有安装任何模块"));
    box.appendChild(emp);
    return;
  }
  if (!list.length) {
    var e2 = el("div", "empty");
    e2.appendChild(ic("search"));
    e2.appendChild(el("div", null, "没有匹配的模块"));
    box.appendChild(e2);
    return;
  }
  list.forEach(function (m) { box.appendChild(moduleCard(m)); });
}

/* ksud 的模块字段是字符串 "true"/"false"，这里统一成布尔 */
function strTrue(v) { return v === true || v === "true" || v === 1 || v === "1"; }
function normalizeModule(m) {
  var o = {};
  for (var k in m) if (Object.prototype.hasOwnProperty.call(m, k)) o[k] = m[k];
  ["enabled", "action", "web", "update", "remove", "metamodule", "mount"].forEach(function (k) {
    if (k in o) o[k] = strTrue(o[k]);
  });
  return o;
}

function moduleCard(m) {
  var card = el("div", "mod" + (m.enabled ? "" : " off"));
  var head = el("div", "mh");

  // 头像：优先用户自定义图标（/api/icon），失败退回首字母
  var ava = el("div", "ava");
  ava.appendChild(el("span", null, (String(m.name || m.id || "?").trim().charAt(0) || "?").toUpperCase()));
  var img = el("img");
  img.alt = "";
  img.style.display = "none";
  img.onload = function () { clear(ava); ava.appendChild(img); img.style.display = "block"; };
  img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
  img.src = P + "/api/icon?id=" + encodeURIComponent(m.id);
  ava.appendChild(img);
  head.appendChild(ava);

  var mt = el("div", "mt");
  var nm = el("div", "m-name");
  nm.appendChild(el("span", null, m.name || m.id));
  mt.appendChild(nm);
  mt.appendChild(el("div", "m-meta",
    "v" + (m.version || "?") + " · " + (m.author || "未知作者") + " · " + m.id));
  if (m.description) mt.appendChild(el("div", "m-desc", m.description));

  var tags = el("div", "tags");
  function tag(iconName, text) {
    var t = el("span", "tag");
    t.appendChild(ic(iconName, "ic"));
    t.appendChild(el("span", null, text));
    return t;
  }
  if (m.enabled) tags.appendChild(tag("circle-check", "已启用"));
  else tags.appendChild(tag("circle-x", "已禁用"));
  if (m.metamodule) tags.appendChild(tag("layers", "元模块"));
  if (m.action) tags.appendChild(tag("play", "有动作脚本"));
  if (m.web) tags.appendChild(tag("globe", "有网页界面"));
  if (m.update) tags.appendChild(tag("upload", "可更新"));
  if (m.remove) tags.appendChild(tag("trash", "待卸载（重启生效）"));
  mt.appendChild(tags);
  head.appendChild(mt);
  card.appendChild(head);

  var acts = el("div", "acts");
  if (m.remove) {
    var undo = el("button", "btn");
    undo.appendChild(ic("rotate-ccw", "ic"));
    undo.appendChild(el("span", null, "撤销卸载"));
    undo.onclick = function () {
      if (state.busy) return;
      state.busy = true;
      post("/api/module/undo-uninstall", { id: m.id }).then(function (r) {
        toast(r.message || (r.ok ? "已撤销卸载" : "操作失败"));
        setTimeout(loadModules, 600);
      }).catch(function (e) { toast("操作失败：" + e.message); })
        .then(function () { state.busy = false; });
    };
    acts.appendChild(undo);
  }
  // 动作脚本：跑起来、把输出显示出来（对应 ksud module action）
  if (m.action) {
    var runAct = el("button", "btn");
    runAct.appendChild(ic("play", "ic"));
    runAct.appendChild(el("span", null, "动作"));
    runAct.onclick = function () {
      if (state.busy) return;
      state.busy = true;
      openSheet("动作脚本", waitBox("正在跑 " + (m.name || m.id) + " 的 action.sh …"), []);
      post("/api/module/action", { id: m.id }).then(function (r) {
        if (!r.ok) { openSheet("跑不动", el("div", "body", r.error || "执行失败"), [{ label: "关闭" }]); return; }
        showActionOutput(m, r);
      }).catch(function (e) {
        openSheet("跑不动", el("div", "body", String((e && e.message) || e)), [{ label: "关闭" }]);
      }).then(function () { state.busy = false; });
    };
    acts.appendChild(runAct);
  }

  // 模块网页（WebUI）：塞进 iframe 里跑（照 DickSU 的 /modweb/<id>/ 做法）
  if (m.web) {
    var webBtn = el("button", "btn");
    webBtn.appendChild(ic("globe", "ic"));
    webBtn.appendChild(el("span", null, "网页"));
    webBtn.onclick = function () { openModuleWeb(m); };
    acts.appendChild(webBtn);
  }

  acts.appendChild(el("div", "sp"));

  var del = el("button", "btn danger");
  del.appendChild(ic("trash", "ic"));
  del.appendChild(el("span", null, "卸载"));
  del.onclick = function () {
    if (m.remove) { toast("这个模块已经标记卸载了，重启手机即可"); return; }
    confirmSheet("卸载模块",
      "确定卸载「" + (m.name || m.id) + "」？\n\n卸载后需要重启手机才真正生效。",
      "卸载", function () {
        state.busy = true;
        post("/api/module/uninstall", { id: m.id }).then(function (r) {
          toast(r.message || r.error || "已提交");
          setTimeout(loadModules, 800);
        }).catch(function (e) { toast("操作失败：" + e.message); })
          .then(function () { state.busy = false; });
      });
  };
  acts.appendChild(del);

  var sw = el("div", "sw" + (m.enabled ? " on" : ""));
  sw.setAttribute("role", "switch");
  sw.onclick = function () {
    if (state.busy) return;
    state.busy = true;
    var want = !m.enabled;
    sw.classList.toggle("on", want);
    post("/api/module/toggle", { id: m.id, enable: want ? "1" : "0" }).then(function (r) {
      if (r.ok) { toast(r.message || "已切换"); setTimeout(loadModules, 600); }
      else { toast(r.error || r.message || "操作失败"); loadModules(); }
    }).catch(function (e) { toast("操作失败：" + e.message); loadModules(); })
      .then(function () { state.busy = false; });
  };
  acts.appendChild(sw);
  card.appendChild(acts);
  return card;
}

/* ── 模块动作脚本输出 / 模块网页（WebUI）── */
function waitBox(text) {
  var box = el("div");
  box.appendChild(el("div", "fld-h", text));
  var bar = el("div", "prog");
  var fill = el("i");
  fill.style.width = "35%";
  bar.appendChild(fill);
  box.appendChild(bar);
  return box;
}

function copyText(text, done) {
  var cb = done || function () { toast("已复制"); };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(cb).catch(function () { fallbackCopy(text, cb); });
  } else {
    fallbackCopy(text, cb);
  }
}

function showActionOutput(m, r) {
  var box = el("div");
  box.appendChild(el("div", "fld-h",
    "退出码 " + r.code + (r.code === 0 ? "（成功）" : "（非 0，脚本报错了）")));
  var pre = el("pre", "out");
  pre.textContent = r.output || "(没有输出)";
  box.appendChild(pre);
  openSheet("动作脚本 · " + (m.name || m.id), box, [
    { label: "复制输出", keepOpen: true, onClick: function () { copyText(r.output || "", function () { toast("输出已复制"); }); } },
    { label: "关闭" }
  ]);
}

function openModuleWeb(m) {
  var box = el("div");
  var bar = el("div", "webui-bar");
  bar.appendChild(el("div", "webui-title", m.name || m.id));
  bar.appendChild(el("div", "sp"));
  // 完整的模块网页需要 KernelSU 的 JS 桥（spawn/包列表等），浏览器里是精简版
  var appLink = el("a", "btn small");
  appLink.textContent = "在管理器里打开";
  appLink.href = "ksu://webui/" + encodeURIComponent(m.id);
  bar.appendChild(appLink);
  box.appendChild(bar);
  var frame = el("iframe", "webui-frame");
  frame.setAttribute("referrerpolicy", "no-referrer");
  frame.src = P + "/modweb/" + encodeURIComponent(m.id) + "/index.html";
  box.appendChild(frame);
  box.appendChild(el("div", "fld-h",
    "这是模块自带的网页界面（本机）。网页里能用的是精简版接口（exec / 提示）；" +
    "模块如果用到更复杂的接口，点右上角「在管理器里打开」用完整版。"));
  openSheet("", box, [{ label: "关闭" }], true);
}

/* ══════════════════════════════════════════════════════════════════
   超级用户
   ──────────────────────────────────────────────────────────────────
   列表按 **uid 分组**（同一个 uid 下的多个包是一组，比如共享 uid 的套件）。
   左边的开关 = 授权/撤销 root；点「配置」进详情改 root 配置。
   ⚠️ 网页拿不到 APK 里的中文应用名（要解析 resources.arsc），所以显示包名。
   ══════════════════════════════════════════════════════════════════ */
function enc(s) { return encodeURIComponent(s == null ? "" : String(s)); }

function loadApps() {
  var box = $("#users");
  clear(box);
  for (var i = 0; i < 3; i++) box.appendChild(el("div", "skel"));
  get("/api/apps").then(function (r) {
    state.apps = (r && r.list) ? r.list : [];
    renderApps();
  }).catch(function (e) {
    clear(box);
    var emp = el("div", "empty err");
    emp.appendChild(ic("triangle-alert"));
    emp.appendChild(el("div", null, "读取失败：" + e.message));
    box.appendChild(emp);
  });
}

function groupedApps() {
  var map = {};
  state.apps.forEach(function (a) {
    (map[a.uid] = map[a.uid] || []).push(a);
  });
  return Object.keys(map).map(function (uid) {
    var apps = map[uid].slice().sort(function (a, b) {
      return String(a.pkg).localeCompare(String(b.pkg), "en");
    });
    return {
      uid: parseInt(uid, 10),
      apps: apps,
      primary: apps[0],
      granted: apps.some(function (a) { return a.granted; }),
      configured: apps.some(function (a) { return a.configured; }),
      system: apps[0].system,
      userId: apps[0].userId
    };
  });
}

function filteredGroups() {
  var list = groupedApps();
  var q = state.appSearch.trim().toLowerCase();
  if (q) {
    list = list.filter(function (g) {
      return g.apps.some(function (a) { return a.pkg.toLowerCase().indexOf(q) >= 0; });
    });
  }
  var f = state.appFilter;
  if (f === "granted") list = list.filter(function (g) { return g.granted; });
  else if (f === "plain") list = list.filter(function (g) { return !g.granted && !g.configured; });
  else if (f === "system") list = list.filter(function (g) { return g.system; });
  list.sort(function (a, b) {
    var ra = a.granted ? 0 : (a.configured ? 1 : 2);
    var rb = b.granted ? 0 : (b.configured ? 1 : 2);
    if (ra !== rb) return ra - rb;
    return String(a.primary.pkg).localeCompare(String(b.primary.pkg), "en");
  });
  return list;
}

function renderApps() {
  var box = $("#users");
  clear(box);
  var list = filteredGroups();
  if (!state.apps.length) {
    var emp = el("div", "empty");
    emp.appendChild(ic("users"));
    emp.appendChild(el("div", null, "没读到任何 App"));
    box.appendChild(emp);
    return;
  }
  if (!list.length) {
    var e2 = el("div", "empty");
    e2.appendChild(ic("search"));
    e2.appendChild(el("div", null, "没有匹配的 App"));
    box.appendChild(e2);
    return;
  }
  list.forEach(function (g) { box.appendChild(appCard(g)); });
}

function appCard(g) {
  var card = el("div", "mod");
  var head = el("div", "mh");

  var ava = el("div", "ava");
  ava.appendChild(el("span", null, (String(g.primary.pkg).charAt(0) || "?").toUpperCase()));
  head.appendChild(ava);

  var mt = el("div", "mt");
  var nm = el("div", "m-name");
  nm.appendChild(el("span", null, g.primary.pkg));
  if (g.granted) nm.appendChild(tagNode("shield-check", "已授权", "ok"));
  if (g.configured && !g.granted) nm.appendChild(tagNode("settings-2", "已配置", "dim"));
  if (g.system) nm.appendChild(tagNode("cpu", "系统", "dim"));
  if (g.userId > 0) nm.appendChild(tagNode("users", "分身 " + g.userId, "dim"));
  mt.appendChild(nm);
  mt.appendChild(el("div", "m-meta",
    "uid " + g.uid + (g.apps.length > 1 ? " · 同组 " + g.apps.length + " 个包" : "")));
  if (g.apps.length > 1) {
    mt.appendChild(el("div", "m-desc", g.apps.map(function (a) { return a.pkg; }).join("\n")));
  }
  head.appendChild(mt);
  card.appendChild(head);

  var acts = el("div", "acts");
  var cfg = el("button", "btn");
  cfg.appendChild(ic("settings-2", "ic"));
  cfg.appendChild(el("span", null, "配置"));
  cfg.onclick = function () { openAppProfile(g); };
  acts.appendChild(cfg);
  acts.appendChild(el("div", "sp"));

  var sw = el("div", "sw" + (g.granted ? " on" : ""));
  sw.setAttribute("role", "switch");
  sw.title = g.granted ? "点击撤销 root" : "点击授予 root";
  sw.onclick = function () {
    if (state.busy) return;
    var want = !g.granted;
    if (!want) {
      confirmSheet("撤销 root 权限",
        "确定撤销「" + g.primary.pkg + "」的 root 权限？\n\n" +
        "撤销后它再调用 su 会被拒绝（非 root 配置会被重置为默认）。",
        "撤销", function () { setAppRoot(g, false); });
      return;
    }
    setAppRoot(g, true);
  };
  acts.appendChild(sw);
  card.appendChild(acts);
  return card;
}

function tagNode(iconName, text, kind) {
  var t = el("span", "tag" + (kind ? " t-" + kind : ""));
  t.appendChild(ic(iconName, "ic"));
  t.appendChild(el("span", null, text));
  return t;
}

function setAppRoot(g, allow) {
  state.busy = true;
  post("/api/app/root", { pkg: g.primary.pkg, uid: g.uid, allow: allow ? "1" : "0" })
    .then(function (r) {
      if (r.ok) { toast(r.message || "已切换"); setTimeout(loadApps, 500); }
      else { toast(r.error || "操作失败"); }
    })
    .catch(function (e) { toast("操作失败：" + e.message); })
    .then(function () { state.busy = false; });
}

/* ── 单个 App 的 root 配置 ── */
function openAppProfile(g) {
  if (state.busy) return;
  state.busy = true;
  get("/api/app/profile", { pkg: g.primary.pkg, uid: g.uid })
    .then(function (r) {
      if (!r.ok || !r.profile) throw new Error(r.error || "读取失败");
      buildProfileSheet(g, r.profile);
    })
    .catch(function (e) { toast("读取失败：" + e.message); })
    .then(function () { state.busy = false; });
}

function fieldRow(label, node, hintText) {
  var wrap = el("div", "fld");
  var top = el("div", "fld-top");
  top.appendChild(el("div", "fld-k", label));
  top.appendChild(node);
  wrap.appendChild(top);
  if (hintText) wrap.appendChild(el("div", "fld-h", hintText));
  return wrap;
}

function textField(value, placeholder, numeric) {
  var i = el("input", "f-in");
  i.type = numeric ? "number" : "text";
  if (numeric) i.inputMode = "numeric";
  i.value = value == null ? "" : String(value);
  i.placeholder = placeholder || "";
  return i;
}

function selectField(options, value) {
  var s = el("select", "f-in");
  options.forEach(function (o) {
    var op = el("option");
    op.value = String(o.v);
    op.textContent = o.t;
    if (String(o.v) === String(value)) op.selected = true;
    s.appendChild(op);
  });
  return s;
}

function buildProfileSheet(g, p) {
  var body = el("div");
  var f = {
    allowSu: !!p.allowSu,
    rootUseDefault: p.rootUseDefault !== false,
    nonRootUseDefault: p.nonRootUseDefault !== false,
    umountModules: p.umountModules !== false
  };

  body.appendChild(el("div", "fld-h",
    g.primary.pkg + " · uid " + g.uid +
    (g.apps.length > 1 ? "（这一组 " + g.apps.length + " 个包共用一份配置）" : "")));

  // 授权开关
  var swAllow = el("div", "sw" + (f.allowSu ? " on" : ""));
  swAllow.setAttribute("role", "switch");
  swAllow.onclick = function () {
    f.allowSu = !f.allowSu;
    swAllow.classList.toggle("on", f.allowSu);
    sync();
  };
  body.appendChild(fieldRow("允许使用 root", swAllow,
    "关掉就是撤销 root（对应 App 详情页那个「允许 root」）"));

  // ── root 配置（允许 root 时才生效）──
  var rootBox = el("div", "fgroup");
  rootBox.appendChild(el("div", "fgroup-t", "root 配置"));
  var swRd = el("div", "sw" + (f.rootUseDefault ? " on" : ""));
  swRd.setAttribute("role", "switch");
  swRd.onclick = function () {
    f.rootUseDefault = !f.rootUseDefault;
    swRd.classList.toggle("on", f.rootUseDefault);
    sync();
  };
  rootBox.appendChild(fieldRow("使用默认配置", swRd,
    "默认 = uid/gid 都是 0、拥有全部能力、SELinux 域 u:r:ksu:s0"));

  var inUid = textField(p.rootUid, "0", true);
  var inGid = textField(p.rootGid, "0", true);
  var inGroups = textField((p.groups || []).join(","), "例：3003,1028", false);
  var inCtx = textField(p.context || "", "u:r:ksu:s0", false);
  var selNs = selectField([
    { v: 0, t: "继承（推荐）" },
    { v: 1, t: "全局" },
    { v: 2, t: "独立" }
  ], p.namespace == null ? 0 : p.namespace);
  var swNnp = el("div", "sw" + (p.noNewPrivs !== false ? " on" : ""));
  swNnp.setAttribute("role", "switch");

  var uidRow = fieldRow("root UID", inUid);
  var gidRow = fieldRow("root GID", inGid);
  var grpRow = fieldRow("附加组 ID", inGroups, "逗号分隔，最多 32 个");
  var ctxRow = fieldRow("SELinux 域", inCtx);
  var nsRow = fieldRow("命名空间", selNs, "独立命名空间 = root 看不到原本的挂载，多数情况用「继承」");
  swNnp.onclick = function () { swNnp.classList.toggle("on"); };
  var nnpRow = fieldRow("禁止在此身份下再提权", swNnp,
    "打开后，从这个 root 身份里再调 su 会被拒绝（更安全）");
  [uidRow, gidRow, grpRow, ctxRow, nsRow, nnpRow].forEach(function (r) { rootBox.appendChild(r); });
  body.appendChild(rootBox);

  // ── 非 root 配置（没授权 root 时才生效）──
  var nrBox = el("div", "fgroup");
  nrBox.appendChild(el("div", "fgroup-t", "非 root 配置"));
  var swNrd = el("div", "sw" + (f.nonRootUseDefault ? " on" : ""));
  swNrd.setAttribute("role", "switch");
  swNrd.onclick = function () {
    f.nonRootUseDefault = !f.nonRootUseDefault;
    swNrd.classList.toggle("on", f.nonRootUseDefault);
    sync();
  };
  nrBox.appendChild(fieldRow("使用默认配置", swNrd));
  var swUm = el("div", "sw" + (f.umountModules ? " on" : ""));
  swUm.setAttribute("role", "switch");
  swUm.onclick = function () {
    f.umountModules = !f.umountModules;
    swUm.classList.toggle("on", f.umountModules);
  };
  nrBox.appendChild(fieldRow("这个 App 不加载模块", swUm,
    "打开 = 它看不到你装的模块（隐藏 root 痕迹常用）"));
  body.appendChild(nrBox);

  body.appendChild(el("div", "fld-h",
    "说明：内核里 root 配置和非 root 配置只能存一份 —— 开了 root 就只保留 root 配置，" +
    "关掉 root 才轮到非 root 配置。"));

  // 按当前状态显示/隐藏
  function sync() {
    rootBox.style.display = f.allowSu ? "" : "none";
    nrBox.style.display = f.allowSu ? "none" : "";
    var custom = f.allowSu && !f.rootUseDefault;
    [uidRow, gidRow, grpRow, ctxRow, nsRow, nnpRow].forEach(function (r) {
      r.style.display = custom ? "" : "none";
    });
    if (f.allowSu && !f.rootUseDefault) f.noNewPrivs = swNnp.classList.contains("on");
  }
  sync();

  openSheet("root 配置", body, [
    { label: "取消" },
    {
      label: "保存", cls: "primary", onClick: function () {
        if (state.busy) return;
        state.busy = true;
        post("/api/app/profile", {
          pkg: g.primary.pkg,
          uid: g.uid,
          allowSu: f.allowSu ? "1" : "0",
          rootUseDefault: f.rootUseDefault ? "1" : "0",
          rootUid: String(parseInt(inUid.value, 10) || 0),
          rootGid: String(parseInt(inGid.value, 10) || 0),
          groups: inGroups.value || "",
          context: inCtx.value || "",
          namespace: selNs.value,
          noNewPrivs: swNnp.classList.contains("on") ? "1" : "0",
          nonRootUseDefault: f.nonRootUseDefault ? "1" : "0",
          umountModules: swUm.classList.contains("on") ? "1" : "0"
        }).then(function (r) {
          toast(r.message || r.error || (r.ok ? "已保存" : "保存失败"));
          if (r.ok) setTimeout(loadApps, 500);
        }).catch(function (e) { toast("保存失败：" + e.message); })
          .then(function () { state.busy = false; });
      }
    }
  ]);
}

/* ══════════════════════════════════════════════════════════════════
   超级用户日志（sulog）
   ══════════════════════════════════════════════════════════════════ */
var SULOG_LIMIT = 400;
var SULOG_TYPE = "all";
var sulogTimer = null;

function loadSulog() {
  var box = $("#sulog");
  clear(box);
  for (var i = 0; i < 3; i++) box.appendChild(el("div", "skel"));
  get("/api/sulog", { limit: SULOG_LIMIT, q: state.sulogQuery }).then(function (r) {
    state.sulog = (r && r.lines) ? r.lines : [];
    state.sulogEnabled = !!(r && r.enabled);
    state.sulogSupported = !(r && r.supported === false);
    renderSulog();
  }).catch(function (e) {
    clear(box);
    var emp = el("div", "empty err");
    emp.appendChild(ic("triangle-alert"));
    emp.appendChild(el("div", null, "读取失败：" + e.message));
    box.appendChild(emp);
  });
}

function sulogTypeName(t) {
  if (t === "ROOT_EXECVE") return "提权执行";
  if (t === "SUCOMPAT") return "su 兼容";
  if (t === "IOCTL_GRANT_ROOT") return "授权 root";
  return t || "?";
}

function parseSulogLine(line) {
  var o = {};
  var re = /([A-Za-z_][A-Za-z0-9_]*)=("(?:[^"\\]|\\.)*"|\S+)/g;
  var m;
  while ((m = re.exec(line)) !== null) {
    var v = m[2];
    if (v.charAt(0) === '"') v = v.slice(1, -1).replace(/\\(.)/g, "$1");
    o[m[1]] = v;
  }
  return o;
}

function fmtTime(ns) {
  var n = Number(ns);
  if (!n) return "";
  var d = new Date(n / 1e6);
  function p(x) { return (x < 10 ? "0" : "") + x; }
  return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " +
         p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
}

function renderSulog() {
  var box = $("#sulog");
  clear(box);

  // 开关行
  var card = el("div", "card tight");
  var r = el("div", "row");
  var k = el("div", "k");
  k.appendChild(ic("scroll-text", "ic ic-sm"));
  k.appendChild(el("span", null, "记录超级用户日志"));
  r.appendChild(k);
  r.appendChild(el("div", "sp"));
  if (!state.sulogSupported) {
    r.appendChild(badge("内核不支持", "warn"));
  } else {
    var sw = el("div", "sw" + (state.sulogEnabled ? " on" : ""));
    sw.setAttribute("role", "switch");
    sw.onclick = function () {
      if (state.busy) return;
      var want = !state.sulogEnabled;
      state.busy = true;
      post("/api/sulog/enable", { enable: want ? "1" : "0" }).then(function (x) {
        toast(x.message || x.error || "已切换");
        setTimeout(loadSulog, 500);
      }).catch(function (e) { toast("操作失败：" + e.message); })
        .then(function () { state.busy = false; });
    };
    r.appendChild(sw);
  }
  card.appendChild(r);
  var clr = el("div", "row");
  var ck = el("div", "k");
  ck.appendChild(ic("trash", "ic ic-sm"));
  ck.appendChild(el("span", null, "清空日志"));
  clr.appendChild(ck);
  clr.appendChild(el("div", "sp"));
  var cbtn = el("button", "btn danger");
  cbtn.appendChild(el("span", null, "清空"));
  cbtn.onclick = function () {
    confirmSheet("清空超级用户日志", "日志文件会被清空（文件保留，之后的记录继续写入）。", "清空", function () {
      state.busy = true;
      post("/api/sulog/clear", {}).then(function (x) {
        toast(x.message || x.error || "已清空");
        setTimeout(loadSulog, 400);
      }).catch(function (e) { toast("操作失败：" + e.message); })
        .then(function () { state.busy = false; });
    });
  };
  clr.appendChild(cbtn);
  card.appendChild(clr);
  box.appendChild(card);

  var list = state.sulog;
  if (SULOG_TYPE !== "all") {
    list = list.filter(function (l) { return l.indexOf("type=" + SULOG_TYPE) >= 0; });
  }
  if (!list.length) {
    var emp = el("div", "empty");
    emp.appendChild(ic("scroll-text"));
    emp.appendChild(el("div", null, state.sulog.length ? "当前筛选下没有记录" : "还没有日志记录"));
    box.appendChild(emp);
    return;
  }

  var wrap = el("div", "loglist");
  list.forEach(function (line) {
    var o = parseSulogLine(line);
    var it = el("div", "logitem");
    var top = el("div", "lgtop");
    top.appendChild(badge(sulogTypeName(o.type), o.retval === "0" ? "ok" : "bad"));
    top.appendChild(el("span", "lgtime", fmtTime(o.ts_ns)));
    if (o.comm) top.appendChild(el("span", "lgcomm", o.comm));
    it.appendChild(top);
    var det = [];
    if (o.uid) det.push("uid " + o.uid);
    if (o.pid) det.push("pid " + o.pid);
    if (o.file) det.push(o.file);
    if (o.argv) det.push(o.argv);
    it.appendChild(el("div", "lgb", det.join(" · ") || line));
    wrap.appendChild(it);
  });
  box.appendChild(wrap);
}

/* ══════════════════════════════════════════════════════════════════
   网页上传装模块
   ══════════════════════════════════════════════════════════════════ */
function uploadSheet(file) {
  var body = el("div");
  var bar = el("div", "prog");
  var fill = el("i");
  bar.appendChild(fill);
  body.appendChild(el("div", "fld-h", file.name + " · " + (file.size / 1048576).toFixed(2) + " MB"));
  body.appendChild(bar);
  var status = el("div", "fld-h", "正在上传…");
  body.appendChild(status);
  // 上传中也能关掉弹层（关掉不会中断上传，装完会 toast 提示）
  openSheet("安装模块", body, [{ label: "关闭" }]);
  fill.style.width = "0%";
  status.textContent = "正在上传…";

  var xhr = new XMLHttpRequest();
  xhr.open("POST", P + "/api/module/install?name=" + enc(file.name), true);
  xhr.setRequestHeader("Content-Type", "application/octet-stream");
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) {
      var pct = Math.round(e.loaded * 100 / e.total);
      fill.style.width = pct + "%";
      status.textContent = "正在上传 " + pct + "%";
    }
  };
  xhr.onload = function () {
    var r = null;
    try { r = JSON.parse(xhr.responseText); } catch (e) {}
    if (r && r.ok) {
      fill.style.width = "100%";
      status.textContent = r.message || "安装完成";
      toast(r.message || "安装完成");
      setTimeout(function () { closeSheet(); loadModules(); }, 1200);
    } else {
      status.textContent = "安装失败：" + ((r && r.error) || ("HTTP " + xhr.status));
      toast("安装失败：" + ((r && r.error) || ("HTTP " + xhr.status)));
    }
  };
  xhr.onerror = function () {
    status.textContent = "上传中断（服务或连接断了）";
    toast("上传中断");
  };
  xhr.send(file);
}

function pickZip() {
  var inp = el("input");
  inp.type = "file";
  inp.accept = ".zip,application/zip";
  inp.style.display = "none";
  inp.onchange = function () {
    var f = inp.files && inp.files[0];
    document.body.removeChild(inp);
    if (!f) return;
    if (!/\.zip$/i.test(f.name)) { toast("只能传 .zip 模块包"); return; }
    confirmSheet("安装模块",
      "确定安装「" + f.name + "」？\n\n" +
      (f.size / 1048576).toFixed(2) + " MB\n" +
      "安装脚本会以 root 身份运行（模块自带），装完需要重启手机才生效。",
      "安装", function () { uploadSheet(f); });
  };
  document.body.appendChild(inp);
  inp.click();
}


var LICENSES = [
  { file: "Lucide-ISC.txt",        name: "Lucide 图标（46 个）", tag: "ISC" },
  { file: "Catppuccin-MIT.txt",    name: "Catppuccin 配色",      tag: "MIT" },
  { file: "Nord-MIT.txt",          name: "Nord 配色",            tag: "MIT" },
  { file: "Dracula-MIT.txt",       name: "Dracula 配色",         tag: "MIT" }
];

function renderAbout() {
  var box = $("#t-about");
  clear(box);

  var c1 = el("div", "card tight");
  var t1 = el("div", "card-title");
  t1.appendChild(ic("info"));
  t1.appendChild(el("span", null, "关于"));
  c1.appendChild(t1);
  c1.appendChild(row("package", "网页管理器", "7kimisu 内置"));
  c1.appendChild(row("shield", "访问范围", "仅本机 127.0.0.1", true, "ok"));
  c1.appendChild(row("link", "访问鉴权", "专属链接（地址里带 256 位随机密钥）", true, "ok"));
  c1.appendChild(row("ban", "防爆破", "密钥 256 位随机，猜不出来", true, "ok"));
  c1.appendChild(row("globe", "外部请求", "零（全部内嵌）", true, "ok"));
  box.appendChild(c1);

  var c2 = el("div", "card tight");
  var t2 = el("div", "card-title");
  t2.appendChild(ic("scroll-text"));
  t2.appendChild(el("span", null, "第三方资源与许可"));
  c2.appendChild(t2);
  LICENSES.forEach(function (l) {
    var r = el("div", "lic");
    r.appendChild(el("div", "nm", l.name));
    r.appendChild(el("span", "tag", l.tag));
    var b = el("button", "btn");
    b.appendChild(el("span", null, "查看"));
    b.onclick = function () { showLicense(l); };
    r.appendChild(b);
    c2.appendChild(r);
  });
  box.appendChild(c2);
  box.appendChild(el("div", "hint",
    "以上资源随 APK 一起分发，许可原文见上面的「查看」。页面不加载任何外部地址，" +
    "配色与图标都已内嵌，断网也能用。"));
}

function showLicense(l) {
  var pre = el("pre", "out", "读取中…");
  openSheet(l.name + " · " + l.tag, pre, [{ label: "关闭" }]);
  fetch(P + "/a/licenses/" + l.file, { cache: "no-store" })
    .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.text(); })
    .then(function (txt) { pre.textContent = txt; })
    .catch(function (e) { pre.textContent = "读取失败：" + e.message; });
}

/* 复制到剪贴板：navigator.clipboard 不可用时退回老办法 */
function fallbackCopy(text, done) {
  var ta = el("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  document.body.appendChild(ta);
  ta.select();
  var ok = false;
  try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
  document.body.removeChild(ta);
  if (ok) done(); else toast("复制失败，请长按地址手动复制");
}

/* ── 标签切换 / 刷新 ── */
function show(tab) {
  state.tab = tab;
  ["status", "users", "modules", "sulog", "about"].forEach(function (t) {
    var sec = $("#t-" + t);
    if (sec) sec.classList.toggle("on", t === tab);
  });
  var btns = document.querySelectorAll("nav button");
  for (var i = 0; i < btns.length; i++) {
    btns[i].classList.toggle("on", btns[i].getAttribute("data-tab") === tab);
  }
  if (tab === "status") loadStatus();
  if (tab === "users") loadApps();
  if (tab === "modules") loadModules();
  if (tab === "sulog") loadSulog();
  if (tab === "about") renderAbout();
}

function refresh() {
  var b = $("#refresh");
  b.classList.add("spin");
  var done = function () { b.classList.remove("spin"); };
  if (state.tab === "modules") { loadModules(); setTimeout(done, 600); }
  else if (state.tab === "users") { loadApps(); setTimeout(done, 600); }
  else if (state.tab === "sulog") { loadSulog(); setTimeout(done, 600); }
  else if (state.tab === "about") { renderAbout(); done(); }
  else { loadStatus(); setTimeout(done, 600); }
}

/* ── 初始化 ── */
function initSprite() {
  // 图标 sprite 是内嵌资源（Lucide，ISC），从同源取回来注入 DOM
  return fetch(P + "/a/icons.svg", { cache: "force-cache" })
    .then(function (r) { return r.ok ? r.text() : ""; })
    .then(function (txt) {
      // 注意：icons.svg 开头是许可注释，所以不能要求它以 "<svg" 开头
      if (txt && txt.indexOf("<symbol") > 0) {
        var holder = el("div");
        holder.style.display = "none";
        holder.innerHTML = txt;          // 静态资源，内容由我们自己打包
        document.body.appendChild(holder);
      }
    })
    .catch(function () { /* 图标失败不影响功能 */ });
}

(function boot() {
  try {
    var saved = localStorage.getItem("7k-webadmin-theme");
    if (saved && THEMES.some(function (t) { return t.id === saved; })) state.theme = saved;
  } catch (e) {}
  applyTheme();
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
    if (state.theme === "auto") applyTheme();
  });

  // 模块页工具条（工具条与列表分开，避免刷新时把输入框清掉）
  var sBox = el("div", "toolbar");
  var sWrap = el("div", "search");
  sWrap.appendChild(ic("search"));
  var sInput = el("input");
  sInput.type = "search";
  sInput.placeholder = "搜索模块名 / id / 作者";
  sInput.oninput = function () { state.search = sInput.value; renderModules(); };
  sWrap.appendChild(sInput);
  sBox.appendChild(sWrap);
  var upBtn = el("button", "btn primary");
  upBtn.appendChild(ic("upload", "ic"));
  upBtn.appendChild(el("span", null, "上传安装"));
  upBtn.onclick = pickZip;
  sBox.appendChild(upBtn);
  $("#mod-toolbar").appendChild(sBox);

  var chips = el("div", "chips");
  [{ id: "default", label: "默认顺序" }, { id: "enabled", label: "启用优先" }, { id: "name", label: "按名称" }]
    .forEach(function (c) {
      var b = el("button", "chip" + (state.sort === c.id ? " on" : ""), c.label);
      b.onclick = function () {
        state.sort = c.id;
        var all = chips.querySelectorAll(".chip");
        for (var i = 0; i < all.length; i++) all[i].classList.remove("on");
        b.classList.add("on");
        renderModules();
      };
      chips.appendChild(b);
    });
  $("#mod-toolbar").appendChild(chips);

  // ── 超级用户页工具条 ──
  var uBox = el("div", "toolbar");
  var uWrap = el("div", "search");
  uWrap.appendChild(ic("search"));
  var uInput = el("input");
  uInput.type = "search";
  uInput.placeholder = "搜索包名（例：com.tencent）";
  uInput.oninput = function () { state.appSearch = uInput.value; renderApps(); };
  uWrap.appendChild(uInput);
  uBox.appendChild(uWrap);
  $("#user-toolbar").appendChild(uBox);
  chipsRow($("#user-toolbar"), [
    { id: "all", label: "全部" },
    { id: "granted", label: "已授权" },
    { id: "plain", label: "未授权" },
    { id: "system", label: "系统应用" }
  ], function () { return state.appFilter; }, function (id) {
    state.appFilter = id;
    renderApps();
  });

  // ── 日志页工具条 ──
  var lBox = el("div", "toolbar");
  var lWrap = el("div", "search");
  lWrap.appendChild(ic("search"));
  var lInput = el("input");
  lInput.type = "search";
  lInput.placeholder = "搜索 uid / 包名 / 路径 / 命令";
  lInput.oninput = function () {
    var v = lInput.value;
    clearTimeout(sulogTimer);
    sulogTimer = setTimeout(function () { state.sulogQuery = v; loadSulog(); }, 350);
  };
  lWrap.appendChild(lInput);
  lBox.appendChild(lWrap);
  $("#sulog-toolbar").appendChild(lBox);
  chipsRow($("#sulog-toolbar"), [
    { id: "all", label: "全部" },
    { id: "ROOT_EXECVE", label: "提权执行" },
    { id: "SUCOMPAT", label: "su 兼容" },
    { id: "IOCTL_GRANT_ROOT", label: "授权 root" }
  ], function () { return SULOG_TYPE; }, function (id) {
    SULOG_TYPE = id;
    renderSulog();
  });
  chipsRow($("#sulog-toolbar"), [
    { id: "200", label: "最近 200 条" },
    { id: "400", label: "最近 400 条" },
    { id: "1000", label: "最近 1000 条" }
  ], function () { return String(SULOG_LIMIT); }, function (id) {
    SULOG_LIMIT = parseInt(id, 10);
    loadSulog();
  });

  var navBtns = document.querySelectorAll("nav button");
  for (var i = 0; i < navBtns.length; i++) {
    (function (b) { b.onclick = function () { show(b.getAttribute("data-tab")); }; })(navBtns[i]);
  }
  // 模块网页（iframe）里发过来的消息：弹提示 / 请求关闭
  window.addEventListener("message", function (ev) {
    if (ev.origin && location.origin && ev.origin !== location.origin) return;
    var d = ev.data;
    if (!d || d.source !== "7kimisu-webui") return;
    if (d.type === "toast") toast(String(d.message || ""));
    if (d.type === "exit") closeSheet();
  });

  $("#refresh").onclick = refresh;
  $("#theme").onclick = themeSheet;
  $("#mask").onclick = function (e) { if (e.target === this) closeSheet(); };

  initSprite().then(function () { show("status"); });
})();
