//! Benchmark-only adapter for the unchanged local Leapfrog library.
use leapfrog::{semi_naive_step, Facts, Rule};
use std::collections::HashSet;
use std::hint::black_box;
use std::time::Instant;

struct Fixture {
    name: &'static str,
    batches: Vec<Vec<Vec<i64>>>,
}

fn rules() -> Vec<Rule> {
    vec![
        Rule { head_pred: "reach", head_vars: vec!["x", "y"],
               body: vec![("edge", vec!["x", "y"])] },
        Rule { head_pred: "extension", head_vars: vec!["x", "z"],
               body: vec![("reach", vec!["x", "y"]), ("edge", vec!["y", "z"])] },
        Rule { head_pred: "reach", head_vars: vec!["x", "z"],
               body: vec![("extension", vec!["x", "z"])] },
    ]
}

fn fixtures() -> Vec<Fixture> {
    vec![
        Fixture { name: "chain", batches: vec![vec![vec![1, 2], vec![2, 3], vec![3, 4]]] },
        Fixture { name: "diamond", batches: vec![vec![vec![1, 2], vec![1, 3], vec![2, 4], vec![3, 4]]] },
        Fixture { name: "cycle", batches: vec![vec![vec![1, 2], vec![2, 3], vec![3, 1]]] },
        Fixture { name: "late-bridge", batches: vec![vec![vec![1, 2]], vec![vec![3, 4]], vec![vec![2, 3]]] },
    ]
}

fn append(initial: &Facts, edges: &[Vec<i64>], rules: &[Rule]) -> Facts {
    let mut facts = initial.clone();
    let known = facts.entry("edge").or_default();
    let fresh: HashSet<_> = edges.iter().filter(|row| !known.contains(*row)).cloned().collect();
    known.extend(fresh.iter().cloned());
    let mut delta = Facts::from([("edge", fresh)]);
    loop {
        if delta.values().all(HashSet::is_empty) {
            return facts;
        } else {
            delta = semi_naive_step(&facts, &delta, rules);
            for (predicate, rows) in &delta {
                facts.entry(predicate).or_default().extend(rows.iter().cloned());
            }
        }
    }
}

fn execute(initial: &Facts, fixture: &Fixture, rules: &[Rule]) -> Facts {
    fixture.batches.iter().fold(initial.clone(), |state, batch| append(&state, batch, rules))
}

fn expected(fixture: &Fixture) -> Facts {
    let edges: HashSet<_> = fixture.batches.iter().flatten().cloned().collect();
    let mut reach = edges.clone();
    loop {
        let mut extension = HashSet::new();
        for left in &reach {
            for right in &edges {
                if left[1] == right[0] {
                    extension.insert(vec![left[0], right[1]]);
                } else {
                    continue;
                }
            }
        }
        let next: HashSet<_> = reach.union(&extension).cloned().collect();
        if next == reach {
            return Facts::from([("edge", edges), ("reach", reach), ("extension", extension)]);
        } else {
            reach = next;
        }
    }
}

fn sample(initial: &Facts, fixture: &Fixture, rules: &[Rule]) -> f64 {
    let started = Instant::now();
    let mut last = Facts::new();
    for _ in 0..1000 {
        last = black_box(execute(black_box(initial), fixture, rules));
    }
    let elapsed = started.elapsed().as_secs_f64() * 1000.0 / 1000.0;
    assert_eq!(expected(fixture), last, "{} closure mismatch", fixture.name);
    elapsed
}

fn benchmark(initial: &Facts, fixture: &Fixture, rules: &[Rule]) {
    for _ in 0..2 {
        black_box(sample(initial, fixture, rules));
    }
    let mut samples: Vec<_> = (0..7).map(|_| sample(initial, fixture, rules)).collect();
    samples.sort_by(f64::total_cmp);
    let expected = expected(fixture);
    println!("{{:case :{} :engine :rust-leapfrog :samples 7 :repetitions 1000 :median-ms {:.6} :min-ms {:.6} :max-ms {:.6} :counts {{:edge {} :reach {} :extension {}}}}}",
             fixture.name, samples[3], samples[0], samples[6],
             expected["edge"].len(), expected["reach"].len(), expected["extension"].len());
}

fn main() {
    let initial = Facts::new();
    let rules = rules();
    for fixture in fixtures() {
        benchmark(&initial, &fixture, &rules);
    }
}
