//! yt-dlp's challenge solver (yt-dlp/ejs) in QuickJS.
//!
//! One runtime, owned by one thread: QuickJS is not thread-safe, so callers send jobs over a
//! channel and wait. Unlike the WebView, nothing here replaces the global object, so the solver
//! scripts and the preprocessed player stay loaded between calls.
//!
//! Every result is JSON: `{"ok":true,...}` or `{"ok":false,"error":"..."}`. Errors never carry
//! the challenge values or anything solved.

use std::cell::Cell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::rc::Rc;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::time::Instant;

use rquickjs::allocator::{Allocator, RustAllocator};
use rquickjs::context::EvalOptions;
use rquickjs::{Context, Ctx, Function, Runtime, Value};
use serde_json::{Map, Value as Json, json};

use crate::streams::{err, ok};

static TIMEOUT_MS: AtomicU64 = AtomicU64::new(15_000);
/// Preprocessing a player took 3.7 s on the PC (a solve 0.19 s); a phone is several times slower,
/// and this runs once per player, mostly in the background prewarm.
static PREPARE_TIMEOUT_MS: AtomicU64 = AtomicU64::new(45_000);
static MEMORY_LIMIT: AtomicUsize = AtomicUsize::new(256 * 1024 * 1024);
/// The worker thread's stack; QuickJS is told a little less so it throws before the thread overflows.
const THREAD_STACK: usize = 16 * 1024 * 1024;
const JS_STACK: usize = 12 * 1024 * 1024;
pub const TIMED_OUT: &str = "solver timed out";

/// Holds the preprocessed player in a JavaScript global and answers in JSON text, so only
/// short strings cross into Rust after the first call.
const GLUE: &str = r#"(function(){
var players = {};
globalThis.__meteora = {
  prepare: function(id, js) {
    try {
      players = {};
      var out = jsc({type: "player", player: js, requests: [], output_preprocessed: true});
      if (!out || out.type !== "result") return JSON.stringify({error: out && out.error ? String(out.error) : "no result"});
      if (typeof out.preprocessed_player !== "string") return JSON.stringify({error: "no preprocessed player"});
      // jsc re-evaluates the whole preprocessed player (~4 MB) on every call: about 450 ms a
      // track on the OnePlus 7T. Evaluate it once, the way the solver's own getFromPrepared
      // does, and keep the two functions; then the text can go. If that shape ever changes,
      // fns stays null and solve falls back to jsc.
      var entry = {pre: out.preprocessed_player, fns: null};
      try {
        var r = {n: null, sig: null};
        Function("_result", entry.pre)(r);
        if (typeof r.n === "function" || typeof r.sig === "function") { entry.fns = r; entry.pre = null; }
      } catch (e) {}
      players[id] = entry;
      return JSON.stringify({});
    } catch (e) { return JSON.stringify({error: String(e)}); }
  },
  solve: function(id, requests) {
    try {
      var entry = players[id];
      if (!entry) return JSON.stringify({error: "player not prepared"});
      var reqs = JSON.parse(requests);
      if (!entry.fns) return JSON.stringify({out: jsc({type: "preprocessed", preprocessed_player: entry.pre, requests: reqs})});
      var responses = reqs.map(function(q) {
        var f = entry.fns[q.type];
        if (typeof f !== "function") return {type: "error", error: "Failed to extract " + q.type + " function"};
        try {
          var data = {};
          q.challenges.forEach(function(c) { data[c] = f(c); });
          return {type: "result", data: data};
        } catch (e) { return {type: "error", error: String(e)}; }
      });
      return JSON.stringify({out: {type: "result", responses: responses}});
    } catch (e) { return JSON.stringify({error: String(e)}); }
  }
};
})();"#;

/// For tests: one timeout for every call; `None` restores the defaults.
#[doc(hidden)]
pub fn set_timeout_ms(ms: Option<u64>) {
    TIMEOUT_MS.store(ms.unwrap_or(15_000), Ordering::Relaxed);
    PREPARE_TIMEOUT_MS.store(ms.unwrap_or(45_000), Ordering::Relaxed);
}

/// For tests: the memory limit used by the next [init].
#[doc(hidden)]
pub fn set_memory_limit(bytes: usize) {
    MEMORY_LIMIT.store(bytes, Ordering::Relaxed);
}

/// Starts a fresh runtime with the two solver scripts. Drops any previous runtime and player.
pub fn init(lib_js: String, core_js: String) -> String {
    run(Job::Init(lib_js, core_js))
}

/// `{"ok":true,"has":bool}`: whether [prepare] already ran for this player.
pub fn has_player(player_id: String) -> String {
    run(Job::HasPlayer(player_id))
}

/// Preprocesses `base.js` and keeps the result inside the runtime for [solve].
pub fn prepare(player_id: String, player_js: String) -> String {
    run(Job::Prepare(player_id, player_js))
}

/// `sig_json` and `n_json` are JSON arrays of challenges. Returns `{"ok":true,"sig":{..},"n":{..}}`,
/// challenge → solved.
pub fn solve(player_id: String, sig_json: String, n_json: String) -> String {
    run(Job::Solve(player_id, sig_json, n_json))
}

enum Job {
    Init(String, String),
    HasPlayer(String),
    Prepare(String, String),
    Solve(String, String, String),
}

type Request = (Job, mpsc::Sender<String>);

fn run(job: Job) -> String {
    static WORKER: OnceLock<Option<mpsc::Sender<Request>>> = OnceLock::new();
    let Some(sender) = WORKER.get_or_init(start_worker) else {
        return err("solver thread did not start");
    };
    let (tx, rx) = mpsc::channel();
    if sender.send((job, tx)).is_err() {
        return err("solver thread stopped");
    }
    rx.recv().unwrap_or_else(|_| err("solver thread stopped"))
}

fn start_worker() -> Option<mpsc::Sender<Request>> {
    let (tx, rx) = mpsc::channel::<Request>();
    std::thread::Builder::new()
        .name("meteora-js".into())
        .stack_size(THREAD_STACK)
        .spawn(move || {
            let mut engine: Option<Engine> = None;
            for (job, reply) in rx {
                let out = match catch_unwind(AssertUnwindSafe(|| handle(&mut engine, job))) {
                    Ok(out) => out,
                    Err(_) => {
                        // The runtime may be half-way through something: start over.
                        engine = None;
                        err("solver panicked")
                    }
                };
                let _ = reply.send(out);
            }
        })
        .ok()?;
    Some(tx)
}

/// Counts what QuickJS allocates, for the peak.
struct Counting(Rc<Stats>);

#[derive(Default)]
struct Stats {
    current: Cell<usize>,
    peak: Cell<usize>,
}

impl Stats {
    fn add(&self, n: usize) {
        let c = self.current.get() + n;
        self.current.set(c);
        if c > self.peak.get() {
            self.peak.set(c);
        }
    }
    fn sub(&self, n: usize) {
        self.current.set(self.current.get().saturating_sub(n));
    }
}

unsafe impl Allocator for Counting {
    fn alloc(&mut self, size: usize) -> *mut u8 {
        let p = RustAllocator.alloc(size);
        if !p.is_null() {
            self.0.add(unsafe { RustAllocator::usable_size(p) });
        }
        p
    }

    fn calloc(&mut self, count: usize, size: usize) -> *mut u8 {
        let p = RustAllocator.calloc(count, size);
        if !p.is_null() {
            self.0.add(unsafe { RustAllocator::usable_size(p) });
        }
        p
    }

    unsafe fn dealloc(&mut self, ptr: *mut u8) {
        unsafe {
            self.0.sub(RustAllocator::usable_size(ptr));
            RustAllocator.dealloc(ptr);
        }
    }

    unsafe fn realloc(&mut self, ptr: *mut u8, new_size: usize) -> *mut u8 {
        unsafe {
            let old = if ptr.is_null() { 0 } else { RustAllocator::usable_size(ptr) };
            let p = RustAllocator.realloc(ptr, new_size);
            if !p.is_null() {
                self.0.sub(old);
                self.0.add(RustAllocator::usable_size(p));
            }
            p
        }
    }

    unsafe fn usable_size(ptr: *mut u8) -> usize {
        unsafe { RustAllocator::usable_size(ptr) }
    }
}

struct Engine {
    ctx: Context,
    // Declared after ctx: the context goes first.
    rt: Runtime,
    player: Option<String>,
    deadline: Rc<Cell<Option<Instant>>>,
    fired: Rc<Cell<bool>>,
    stats: Rc<Stats>,
}

/// Why a call into JavaScript failed.
enum Failure {
    /// The solver answered with an error; the runtime is fine.
    Js(String),
    /// Timed out, out of memory, or an uncatchable exception: the runtime should not be reused.
    Broken(String),
}

fn handle(engine: &mut Option<Engine>, job: Job) -> String {
    match job {
        Job::Init(lib, core) => {
            *engine = None;
            match Engine::new(&lib, &core) {
                Ok((e, ms)) => {
                    let peak = e.stats.peak.get();
                    *engine = Some(e);
                    ok(json!({ "ms": ms, "heap_peak": peak }))
                }
                Err(e) => err(&clean(&e, &[])),
            }
        }
        Job::HasPlayer(id) => {
            let has = engine.as_ref().is_some_and(|e| e.player.as_deref() == Some(id.as_str()));
            ok(json!({ "has": has }))
        }
        Job::Prepare(id, js) => {
            let Some(e) = engine.as_mut() else { return err("solver not initialised") };
            e.player = None;
            let t = Instant::now();
            match e.call("prepare", &id, js) {
                Ok(_) => {
                    // Parsing the player leaves cycles behind; the heap figure below is what stays.
                    e.rt.run_gc();
                    e.player = Some(id);
                    ok(json!({ "ms": t.elapsed().as_millis() as u64, "heap": e.stats.current.get(), "heap_peak": e.stats.peak.get() }))
                }
                Err(f) => fail(engine, f, &[]),
            }
        }
        Job::Solve(id, sig_json, n_json) => {
            let (Ok(sig), Ok(n)) = (serde_json::from_str::<Vec<String>>(&sig_json), serde_json::from_str::<Vec<String>>(&n_json)) else {
                return err("challenges are not JSON string arrays");
            };
            if sig.is_empty() && n.is_empty() {
                return ok(json!({ "sig": {}, "n": {}, "ms": 0 }));
            }
            let Some(e) = engine.as_mut() else { return err("solver not initialised") };
            if e.player.as_deref() != Some(id.as_str()) {
                return err("player not prepared");
            }
            let mut requests = Vec::new();
            if !sig.is_empty() {
                requests.push(json!({ "type": "sig", "challenges": sig }));
            }
            if !n.is_empty() {
                requests.push(json!({ "type": "n", "challenges": n }));
            }
            let t = Instant::now();
            let all: Vec<String> = sig.iter().chain(n.iter()).cloned().collect();
            let raw = match e.call("solve", &id, Json::Array(requests).to_string()) {
                Ok(raw) => raw,
                Err(f) => return fail(engine, f, &all),
            };
            let ms = t.elapsed().as_millis() as u64;
            let peak = e.stats.peak.get();
            match parse_answers(&raw, &sig, &n) {
                Ok((s, nn)) => ok(json!({ "sig": s, "n": nn, "ms": ms, "heap_peak": peak })),
                Err(m) => err(&clean(&m, &all)),
            }
        }
    }
}

fn fail(engine: &mut Option<Engine>, f: Failure, challenges: &[String]) -> String {
    match f {
        Failure::Js(m) => err(&clean(&m, challenges)),
        Failure::Broken(m) => {
            *engine = None;
            err(&clean(&m, challenges))
        }
    }
}

impl Engine {
    fn new(lib: &str, core: &str) -> Result<(Engine, u64), String> {
        let t = Instant::now();
        let stats = Rc::new(Stats::default());
        let rt = Runtime::new_with_alloc(Counting(stats.clone())).map_err(|e| format!("no runtime: {e}"))?;
        rt.set_memory_limit(MEMORY_LIMIT.load(Ordering::Relaxed));
        rt.set_max_stack_size(JS_STACK);
        let deadline: Rc<Cell<Option<Instant>>> = Rc::new(Cell::new(None));
        let fired = Rc::new(Cell::new(false));
        {
            let (deadline, fired) = (deadline.clone(), fired.clone());
            rt.set_interrupt_handler(Some(Box::new(move || match deadline.get() {
                Some(d) if Instant::now() >= d => {
                    fired.set(true);
                    true
                }
                _ => false,
            })));
        }
        let ctx = Context::full(&rt).map_err(|e| format!("no context: {e}"))?;
        let engine = Engine { ctx, rt, player: None, deadline, fired, stats };
        // The protocol in docs/high-quality-plan.md, then the glue.
        for (what, src) in [("lib.min.js", lib), ("assign", "Object.assign(globalThis, lib);"), ("core.min.js", core), ("glue", GLUE)] {
            engine.eval(src).map_err(|f| match f {
                Failure::Js(m) | Failure::Broken(m) => format!("{what}: {m}"),
            })?;
        }
        Ok((engine, t.elapsed().as_millis() as u64))
    }

    fn arm(&self, ms: u64) {
        self.fired.set(false);
        self.deadline.set(Some(Instant::now() + std::time::Duration::from_millis(ms)));
    }

    fn disarm(&self) {
        self.deadline.set(None);
    }

    fn eval(&self, src: &str) -> Result<(), Failure> {
        let ms = TIMEOUT_MS.load(Ordering::Relaxed);
        self.arm(ms);
        let r = self.ctx.with(|ctx| {
            let mut opts = EvalOptions::default();
            // The scripts are written for a browser's sloppy-mode global scope, as in Node or a WebView.
            opts.strict = false;
            let r = ctx.eval_with_options::<(), _>(src, opts);
            r.map_err(|e| self.failure(&ctx, e, ms))
        });
        self.disarm();
        r
    }

    /// `__meteora[method](id, arg)`; the glue returns JSON text with either `error` or the answer.
    fn call(&self, method: &str, id: &str, arg: String) -> Result<String, Failure> {
        let ms = if method == "prepare" { PREPARE_TIMEOUT_MS.load(Ordering::Relaxed) } else { TIMEOUT_MS.load(Ordering::Relaxed) };
        self.arm(ms);
        let r = self.ctx.with(|ctx| {
            let run = || -> rquickjs::Result<String> {
                let glue: rquickjs::Object = ctx.globals().get("__meteora")?;
                let f: Function = glue.get(method)?;
                f.call((rquickjs::function::This(glue), id, arg))
            };
            run().map_err(|e| self.failure(&ctx, e, ms))
        });
        self.disarm();
        let raw = r?;
        let v: Json = serde_json::from_str(&raw).map_err(|_| Failure::Js("solver returned no JSON".into()))?;
        if let Some(e) = v.get("error") {
            let m = e.as_str().unwrap_or("unknown error").to_string();
            return Err(if m.contains("out of memory") { Failure::Broken(m) } else { Failure::Js(m) });
        }
        Ok(raw)
    }

    fn failure(&self, ctx: &Ctx<'_>, e: rquickjs::Error, ms: u64) -> Failure {
        if self.fired.get() {
            let _ = ctx.catch();
            return Failure::Broken(format!("{TIMED_OUT} after {ms} ms"));
        }
        let m = match e {
            rquickjs::Error::Exception => describe(ctx.catch()),
            other => other.to_string(),
        };
        Failure::Broken(m)
    }
}

fn describe(v: Value<'_>) -> String {
    if let Some(ex) = v.as_exception() {
        return ex.message().unwrap_or_else(|| "exception".into());
    }
    v.as_string().and_then(|s| s.to_string().ok()).unwrap_or_else(|| "exception".into())
}

/// `{"out": <jsc result>}` → the two challenge → solved maps. Same checks as the WebView's parseResult.
fn parse_answers(raw: &str, sig: &[String], n: &[String]) -> Result<(Map<String, Json>, Map<String, Json>), String> {
    let root: Json = serde_json::from_str(raw).map_err(|_| "solver returned no JSON".to_string())?;
    let out = root.get("out").and_then(Json::as_object).ok_or("solver returned no result")?;
    let failed = |o: &Map<String, Json>| o.get("type").and_then(Json::as_str) != Some("result");
    let error = |o: &Map<String, Json>| format!("solver: {}", o.get("error").and_then(Json::as_str).unwrap_or("unknown error"));
    if failed(out) {
        return Err(error(out));
    }
    let responses = out.get("responses").and_then(Json::as_array).map(Vec::as_slice).unwrap_or(&[]);
    let asked: Vec<&[String]> = [sig, n].into_iter().filter(|c| !c.is_empty()).collect();
    if responses.len() != asked.len() {
        return Err(format!("solver: {} responses for {} requests", responses.len(), asked.len()));
    }
    let mut answers = Vec::new();
    for (challenges, r) in asked.iter().zip(responses) {
        let r = r.as_object().ok_or("solver: bad response")?;
        if failed(r) {
            return Err(error(r));
        }
        let data = r.get("data").and_then(Json::as_object).ok_or("solver: no data")?;
        let mut m = Map::new();
        for c in challenges.iter() {
            let v = data.get(c).and_then(Json::as_str).ok_or("solver left a challenge unsolved")?;
            m.insert(c.clone(), Json::String(v.to_string()));
        }
        answers.push(m);
    }
    let mut it = answers.into_iter();
    let s = if sig.is_empty() { Map::new() } else { it.next().unwrap_or_default() };
    let nn = if n.is_empty() { Map::new() } else { it.next().unwrap_or_default() };
    Ok((s, nn))
}

/// The error's first line, short, with the challenge values taken out: those must never reach a log.
pub(crate) fn clean(error: &str, challenges: &[String]) -> String {
    let mut e: String = error.lines().next().unwrap_or("").chars().take(200).collect();
    for c in challenges {
        if !c.is_empty() {
            e = e.replace(c.as_str(), "…");
        }
    }
    if e.trim().is_empty() { "unknown error".into() } else { e }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &[&str]) -> Vec<String> {
        s.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn answers_parsed() {
        let raw = r#"{"out":{"type":"result","responses":[{"type":"result","data":{"s1":"S1"}},{"type":"result","data":{"n1":"N1"}}]}}"#;
        let (s, n) = parse_answers(raw, &v(&["s1"]), &v(&["n1"])).unwrap();
        assert_eq!(s.get("s1").unwrap(), "S1");
        assert_eq!(n.get("n1").unwrap(), "N1");
        let raw = r#"{"out":{"type":"result","responses":[{"type":"result","data":{"n1":"N1"}}]}}"#;
        let (s, n) = parse_answers(raw, &[], &v(&["n1"])).unwrap();
        assert!(s.is_empty());
        assert_eq!(n.get("n1").unwrap(), "N1");
    }

    #[test]
    fn answer_errors() {
        let (sig, n) = (v(&["secretS"]), v(&["secretN"]));
        let all: Vec<String> = sig.iter().chain(n.iter()).cloned().collect();
        let fails = |raw: &str| clean(&parse_answers(raw, &sig, &n).unwrap_err(), &all);
        let top = fails(r#"{"out":{"type":"error","error":"cannot solve secretS\nstack"}}"#);
        assert!(!top.contains("secretS") && !top.contains("stack"));
        let inner = fails(r#"{"out":{"type":"result","responses":[{"type":"error","error":"bad secretN"},{"type":"result","data":{}}]}}"#);
        assert!(!inner.contains("secretN"));
        fails(r#"{"out":{"type":"result","responses":[{"type":"result","data":{}},{"type":"result","data":{"secretN":"x"}}]}}"#);
        fails(r#"{"out":{"type":"result","responses":[]}}"#);
        fails("null");
        fails("not json");
    }
}
