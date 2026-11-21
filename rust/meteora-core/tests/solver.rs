//! The QuickJS solver, driven through the same functions the JNI layer calls.
//! The real-player test reads base.js from `METEORA_PLAYER_JS` and is skipped without it.

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Instant;

use meteora_core::solver;
use serde_json::Value;

/// One solver runtime per process: these tests take turns.
static SOLVER: Mutex<()> = Mutex::new(());

fn repo() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn scripts() -> (String, String) {
    let dir = repo().join("app/src/main/assets/ejs");
    (std::fs::read_to_string(dir.join("lib.min.js")).unwrap(), std::fs::read_to_string(dir.join("core.min.js")).unwrap())
}

fn parse(s: &str) -> Value {
    serde_json::from_str(s).expect("result is JSON")
}

fn ok(s: String) -> Value {
    let v = parse(&s);
    assert_eq!(v["ok"], true, "{s}");
    v
}

fn error(s: String) -> String {
    let v = parse(&s);
    assert_eq!(v["ok"], false, "{s}");
    v["error"].as_str().unwrap().to_string()
}

const SIG: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz01";
const SIG_SOLVED: &str = "10zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA_B9876A43210zyxwvutsr-ponmlkjihgfedcbaZY5WVUTSRQPONMLKJIHGFEDCq";
const N: &str = "ZdZIqFPQK-Ty8wId";
const N_SOLVED: &str = "uk0U1apoQu2r";

#[test]
fn solves_real_player_three_times_on_one_runtime() {
    let Ok(path) = std::env::var("METEORA_PLAYER_JS") else {
        eprintln!("METEORA_PLAYER_JS not set: skipped");
        return;
    };
    let _g = SOLVER.lock().unwrap_or_else(|e| e.into_inner());
    let player = std::fs::read_to_string(path).unwrap();
    let (lib, core) = scripts();

    let t = Instant::now();
    let init = ok(solver::init(lib, core));
    eprintln!("init: {} ms", t.elapsed().as_millis());
    let _ = init;
    assert_eq!(ok(solver::has_player("6b3d0364".into()))["has"], false);

    let t = Instant::now();
    let prep = ok(solver::prepare("6b3d0364".into(), player));
    let prep_ms = t.elapsed().as_millis();
    assert_eq!(ok(solver::has_player("6b3d0364".into()))["has"], true);
    assert_eq!(ok(solver::has_player("other".into()))["has"], false);

    for i in 0..3 {
        let t = Instant::now();
        let r = ok(solver::solve("6b3d0364".into(), format!("[\"{SIG}\"]"), format!("[\"{N}\"]")));
        let ms = t.elapsed().as_millis();
        assert_eq!(r["sig"][SIG], SIG_SOLVED);
        assert_eq!(r["n"][N], N_SOLVED);
        if i == 0 {
            eprintln!("first solve: prepare {prep_ms} ms + solve {ms} ms = {} ms", prep_ms + ms);
        } else {
            eprintln!("solve {}: {ms} ms", i + 1);
        }
        eprintln!("  heap peak so far: {:.1} MB", r["heap_peak"].as_f64().unwrap() / 1048576.0);
    }
    eprintln!("after prepare: heap {:.1} MB", prep["heap"].as_f64().unwrap() / 1048576.0);

    // Only n, as for a plain-URL format.
    let r = ok(solver::solve("6b3d0364".into(), "[]".into(), format!("[\"{N}\"]")));
    assert_eq!(r["n"][N], N_SOLVED);
    assert_eq!(r["sig"].as_object().unwrap().len(), 0);
    // Another player id was never prepared.
    error(solver::solve("other".into(), "[]".into(), format!("[\"{N}\"]")));
}

#[test]
fn not_initialised_or_not_prepared() {
    let _g = SOLVER.lock().unwrap_or_else(|e| e.into_inner());
    let (lib, core) = scripts();
    ok(solver::init(lib, core));
    let e = error(solver::solve("nope".into(), "[\"secret\"]".into(), "[]".into()));
    assert!(e.contains("not prepared"), "{e}");
    // Nothing to solve needs no player.
    ok(solver::solve("nope".into(), "[]".into(), "[]".into()));
    error(solver::solve("nope".into(), "not json".into(), "[]".into()));
}

#[test]
fn broken_scripts_fail_cleanly() {
    let _g = SOLVER.lock().unwrap_or_else(|e| e.into_inner());
    let e = error(solver::init("this is not javascript(".into(), "".into()));
    assert!(e.starts_with("lib.min.js:"), "{e}");
    // A solver that answers with an error, mentioning the challenge.
    ok(solver::init(
        "var lib={};".into(),
        r#"function jsc(i){ if(i.type==="player") return {type:"result",responses:[],preprocessed_player:"P"}; return {type:"error",error:"cannot solve "+i.requests[0].challenges[0]+"\nat line 2"}; }"#.into(),
    ));
    ok(solver::prepare("p".into(), "player".into()));
    let e = error(solver::solve("p".into(), "[\"secretS\"]".into(), "[]".into()));
    assert!(!e.contains("secretS") && !e.contains("line 2"), "{e}");
    // A thrown exception.
    ok(solver::init("var lib={};".into(), "function jsc(i){ throw new Error('boom'); }".into()));
    let e = error(solver::prepare("p".into(), "player".into()));
    assert!(e.contains("boom"), "{e}");
    assert_eq!(ok(solver::has_player("p".into()))["has"], false);
}

#[test]
fn runaway_solve_times_out() {
    let _g = SOLVER.lock().unwrap_or_else(|e| e.into_inner());
    ok(solver::init("var lib={};".into(), "function jsc(i){ for(;;){} }".into()));
    solver::set_timeout_ms(Some(500));
    let t = Instant::now();
    let e = error(solver::prepare("p".into(), "player".into()));
    solver::set_timeout_ms(None);
    assert!(e.starts_with(solver::TIMED_OUT), "{e}");
    assert!(t.elapsed().as_millis() < 5_000);
    // The runtime is dropped: it has to be set up again.
    let e = error(solver::prepare("p".into(), "player".into()));
    assert!(e.contains("not initialised"), "{e}");
}

#[test]
fn memory_limit_is_an_error_not_a_crash() {
    let _g = SOLVER.lock().unwrap_or_else(|e| e.into_inner());
    solver::set_memory_limit(32 * 1024 * 1024);
    let r = solver::init("var lib={};".into(), "function jsc(i){ var a=[]; for(;;) a.push(new Array(100000).fill(1)); }".into());
    solver::set_memory_limit(256 * 1024 * 1024);
    ok(r);
    let e = error(solver::prepare("p".into(), "player".into()));
    assert!(e.contains("out of memory"), "{e}");
    let e = error(solver::prepare("p".into(), "player".into()));
    assert!(e.contains("not initialised"), "{e}");
}
