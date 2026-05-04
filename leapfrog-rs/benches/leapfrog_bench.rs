use criterion::{black_box, criterion_group, criterion_main, Criterion};
use leapfrog::{lftj, semi_naive, demo_relations, Facts, Relation, ResultMode, Rule};
use std::collections::HashSet;

fn chain_relations(n: usize) -> (Vec<Relation>, Vec<&'static str>) {
    // R_i(i, i+1) for i in 0..n-1 — join order x0, x1, ..., x_{n-1}
    let mut rels = Vec::with_capacity(n - 1);
    let mut var_names: Vec<String> = (0..n).map(|i| format!("x{}", i)).collect();
    // We need &'static str for vars — use leak for synthetic benchmark only
    let vars_static: Vec<&'static str> = var_names
        .iter_mut()
        .map(|s| {
            let leaked: &'static mut str = Box::leak(std::mem::take(s).into_boxed_str());
            &*leaked
        })
        .collect();

    for i in 0..(n - 1) {
        let v0 = vars_static[i];
        let v1 = vars_static[i + 1];
        let tuples: Vec<Vec<i64>> = (0..n as i64 - 1)
            .map(|j| vec![j, j + 1])
            .collect();
        rels.push(Relation::new("R", vec![v0, v1], &tuples));
    }
    (rels, vars_static)
}

fn bench_lftj_chain(c: &mut Criterion) {
    let (rels, vars) = chain_relations(8);
    c.bench_function("lftj_chain_n8_tuples", |b| {
        b.iter(|| {
            let r = rels.clone();
            black_box(lftj(
                r,
                vars.clone(),
                black_box(ResultMode::Tuples),
            ))
        })
    });
}

fn bench_lftj_demo(c: &mut Criterion) {
    c.bench_function("lftj_demo_R_S_T", |b| {
        b.iter(|| {
            black_box(lftj(
                demo_relations(),
                vec!["x", "y", "z"],
                ResultMode::Tuples,
            ))
        })
    });
}

fn bench_semi_naive_path(c: &mut Criterion) {
    // Line graph 1..=120 — enough work to measure without multi-minute Criterion runs.
    const N: i64 = 120;
    let rules = vec![
        Rule {
            head_pred: "path",
            head_vars: vec!["x", "y"],
            body: vec![("edge", vec!["x", "y"])],
        },
        Rule {
            head_pred: "path",
            head_vars: vec!["x", "z"],
            body: vec![("path", vec!["x", "y"]), ("edge", vec!["y", "z"])],
        },
    ];
    c.bench_function("semi_naive_path_edges_120", |b| {
        b.iter(|| {
            let mut f = Facts::new();
            let mut e = HashSet::new();
            for i in 1i64..=N {
                e.insert(vec![i, i + 1]);
            }
            f.insert("edge", e);
            black_box(semi_naive(f, &rules))
        })
    });
}

criterion_group!(benches, bench_lftj_demo, bench_lftj_chain, bench_semi_naive_path);
criterion_main!(benches);
