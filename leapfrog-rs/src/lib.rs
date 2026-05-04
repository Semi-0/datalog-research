//! Leapfrog triejoin and tiny semi-naive Datalog — Rust port of `leapfrog.clj`.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;

// ---------------------------------------------------------------------------
// Persistent trie
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Trie {
    children: Arc<BTreeMap<i64, Trie>>,
}

impl Trie {
    pub fn empty() -> Self {
        Self::default()
    }

    pub fn insert(mut self, tuple: &[i64]) -> Self {
        if tuple.is_empty() {
            return self;
        }
        let k = tuple[0];
        let rest = &tuple[1..];
        let mut m = (*self.children).clone();
        let child = m.remove(&k).unwrap_or_default();
        m.insert(k, child.insert(rest));
        self.children = Arc::new(m);
        self
    }

    pub fn from_tuples(tuples: impl IntoIterator<Item = impl AsRef<[i64]>>) -> Self {
        tuples
            .into_iter()
            .fold(Trie::empty(), |t, row| t.insert(row.as_ref()))
    }

    fn root(&self) -> &Arc<BTreeMap<i64, Trie>> {
        &self.children
    }
}

// ---------------------------------------------------------------------------
// Mutable trie iterator
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
struct Frame {
    node: Arc<BTreeMap<i64, Trie>>,
    keys: Vec<i64>,
    pos: usize,
}

impl Frame {
    fn new(node: Arc<BTreeMap<i64, Trie>>) -> Self {
        let keys: Vec<i64> = node.keys().copied().collect();
        Self { node, keys, pos: 0 }
    }

    fn end(&self) -> bool {
        self.pos >= self.keys.len()
    }

    fn key(&self) -> Option<i64> {
        self.keys.get(self.pos).copied()
    }

    fn child_trie(&self) -> Option<&Trie> {
        let k = self.key()?;
        self.node.get(&k)
    }

    fn next(&mut self) {
        if !self.end() {
            self.pos += 1;
        }
    }

    fn seek(&mut self, target: i64) {
        self.pos = self.keys.partition_point(|k| *k < target);
    }
}

#[derive(Debug)]
pub struct TrieIter {
    stack: Vec<Frame>,
}

impl TrieIter {
    pub fn new(trie: &Trie) -> Self {
        Self {
            stack: vec![Frame::new(Arc::clone(trie.root()))],
        }
    }

    pub fn end(&self) -> bool {
        self.stack.last().map_or(true, |f| f.end())
    }

    pub fn key(&self) -> Option<i64> {
        self.stack.last().and_then(|f| f.key())
    }

    pub fn depth(&self) -> usize {
        self.stack.len().saturating_sub(1)
    }

    pub fn next(&mut self) {
        if let Some(f) = self.stack.last_mut() {
            f.next();
        }
    }

    pub fn seek(&mut self, target: i64) {
        if let Some(f) = self.stack.last_mut() {
            f.seek(target);
        }
    }

    pub fn open(&mut self) {
        let frame = self.stack.last().expect("open");
        if frame.end() {
            panic!("cannot open exhausted iterator");
        }
        let child = frame
            .child_trie()
            .expect("missing child at current key");
        self.stack
            .push(Frame::new(Arc::clone(child.root())));
    }

    pub fn up(&mut self) {
        if self.stack.len() <= 1 {
            panic!("iterator has no parent level");
        }
        self.stack.pop();
    }

    pub fn snapshot(&self) -> IterSnapshot {
        IterSnapshot {
            stack: self.stack.clone(),
        }
    }

    pub fn restore(&mut self, snap: &IterSnapshot) {
        self.stack = snap.stack.clone();
    }
}

#[derive(Clone, Debug)]
pub struct IterSnapshot {
    stack: Vec<Frame>,
}

// ---------------------------------------------------------------------------
// Relation + state
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct Relation {
    pub name: &'static str,
    pub vars: Vec<&'static str>,
    pub trie: Trie,
}

impl Relation {
    pub fn new(name: &'static str, vars: Vec<&'static str>, tuples: &[Vec<i64>]) -> Self {
        Self {
            name,
            vars,
            trie: Trie::from_tuples(tuples),
        }
    }
}

pub struct State {
    pub rel: Relation,
    pub iter: TrieIter,
}

impl State {
    pub fn new(rel: Relation) -> Self {
        let trie = rel.trie.clone();
        Self {
            rel,
            iter: TrieIter::new(&trie),
        }
    }

    pub fn current_var(&self) -> Option<&'static str> {
        let d = self.iter.depth();
        self.rel.vars.get(d).copied()
    }
}

pub fn active_indices(states: &[State], var: &str) -> Vec<usize> {
    states
        .iter()
        .enumerate()
        .filter(|(_, s)| s.current_var() == Some(var))
        .map(|(i, _)| i)
        .collect()
}

pub type StateSnapshot = Vec<(usize, IterSnapshot)>;

pub fn snapshot_states(states: &[State]) -> StateSnapshot {
    states
        .iter()
        .enumerate()
        .map(|(i, s)| (i, s.iter.snapshot()))
        .collect()
}

pub fn restore_states(states: &mut [State], snap: &StateSnapshot) {
    for (i, it_snap) in snap {
        states[*i].iter.restore(it_snap);
    }
}

// ---------------------------------------------------------------------------
// Leapfrog (indexed — no aliasing issues)
// ---------------------------------------------------------------------------

fn leapfrog_key_indexed(states: &mut [State], active: &[usize]) -> Option<i64> {
    if active.is_empty() {
        panic!("no iterators for leapfrog join");
    }
    loop {
        if active.iter().any(|&i| states[i].iter.end()) {
            return None;
        }
        let mut ord: Vec<usize> = active.to_vec();
        ord.sort_by_key(|&i| states[i].iter.key().unwrap_or(i64::MAX));
        let least = ord[0];
        let greatest = *ord.last().unwrap();
        let lk = states[least].iter.key()?;
        let gk = states[greatest].iter.key()?;
        if lk == gk {
            return Some(lk);
        }
        states[least].iter.seek(gk);
    }
}

fn advance_one_indexed(states: &mut [State], active: &[usize]) {
    if active.is_empty() {
        return;
    }
    if active.iter().any(|&i| states[i].iter.end()) {
        return;
    }
    let mut ord: Vec<usize> = active.to_vec();
    ord.sort_by_key(|&i| states[i].iter.key().unwrap_or(i64::MAX));
    let least = ord[0];
    states[least].iter.next();
}

// ---------------------------------------------------------------------------
// LFTJ
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResultMode {
    Bindings,
    Tuples,
}

pub type Env = HashMap<&'static str, i64>;

pub fn result_tuple(vars: &[&'static str], env: &Env) -> Vec<i64> {
    vars.iter().map(|&v| *env.get(v).expect("missing var")).collect()
}

/// Leapfrog triejoin.
///
/// - `Tuples`: each row is values in `vars` order (Clojure `:tuples`).
/// - `Bindings`: each row is `[v0, val0, v1, val1, ...]` flattened pairs (Clojure `:bindings`).
pub fn lftj(relations: Vec<Relation>, vars: Vec<&'static str>, mode: ResultMode) -> Vec<Vec<i64>> {
    let mut states: Vec<State> = relations.into_iter().map(State::new).collect();
    let mut out: Vec<Vec<i64>> = Vec::new();
    let mut env = Env::new();
    let vars_sl = vars.as_slice();

    match mode {
        ResultMode::Tuples => search_tuples(&mut states, vars_sl, vars_sl, &mut env, &mut out),
        ResultMode::Bindings => search_bindings(&mut states, vars_sl, vars_sl, &mut env, &mut out),
    }
    out
}

fn search_tuples(
    states: &mut [State],
    all_vars: &[&'static str],
    remaining: &[&'static str],
    env: &mut Env,
    out: &mut Vec<Vec<i64>>,
) {
    if remaining.is_empty() {
        out.push(result_tuple(all_vars, env));
        return;
    }

    let var = remaining[0];
    let rest = &remaining[1..];
    let entry_snap = snapshot_states(states);
    let active = active_indices(states, var);
    assert!(
        !active.is_empty(),
        "no active relation for variable {}",
        var
    );

    loop {
        let value = leapfrog_key_indexed(states, &active);
        let Some(value) = value else { break };

        let before_child = snapshot_states(states);
        env.insert(var, value);

        if rest.is_empty() {
            out.push(result_tuple(all_vars, env));
        } else {
            for &i in &active {
                states[i].iter.open();
            }
            search_tuples(states, all_vars, rest, env, out);
            for &i in &active {
                states[i].iter.up();
            }
        }

        env.remove(var);
        restore_states(states, &before_child);
        advance_one_indexed(states, &active);
    }

    restore_states(states, &entry_snap);
}

/// Bindings rows: `[var_index_0, val_0, var_index_1, val_1, ...]` in `all_vars` order.
fn emit_bindings_row(all_vars: &[&'static str], env: &Env) -> Vec<i64> {
    let mut row = Vec::with_capacity(all_vars.len() * 2);
    for (i, &name) in all_vars.iter().enumerate() {
        row.push(i as i64);
        row.push(*env.get(name).expect("missing var"));
    }
    row
}

fn search_bindings(
    states: &mut [State],
    all_vars: &[&'static str],
    remaining: &[&'static str],
    env: &mut Env,
    out: &mut Vec<Vec<i64>>,
) {
    if remaining.is_empty() {
        out.push(emit_bindings_row(all_vars, env));
        return;
    }

    let var = remaining[0];
    let rest = &remaining[1..];
    let entry_snap = snapshot_states(states);
    let active = active_indices(states, var);
    assert!(
        !active.is_empty(),
        "no active relation for variable {}",
        var
    );

    loop {
        let value = leapfrog_key_indexed(states, &active);
        let Some(value) = value else { break };

        let before_child = snapshot_states(states);
        env.insert(var, value);

        if rest.is_empty() {
            out.push(emit_bindings_row(all_vars, env));
        } else {
            for &i in &active {
                states[i].iter.open();
            }
            search_bindings(states, all_vars, rest, env, out);
            for &i in &active {
                states[i].iter.up();
            }
        }

        env.remove(var);
        restore_states(states, &before_child);
        advance_one_indexed(states, &active);
    }

    restore_states(states, &entry_snap);
}

// ---------------------------------------------------------------------------
// Semi-naive Datalog
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct Rule {
    pub head_pred: &'static str,
    pub head_vars: Vec<&'static str>,
    pub body: Vec<(&'static str, Vec<&'static str>)>,
}

pub type Facts = HashMap<&'static str, HashSet<Vec<i64>>>;

fn body_vars(body: &[(&'static str, Vec<&'static str>)]) -> Vec<&'static str> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for (_, vs) in body {
        for &v in vs {
            if seen.insert(v) {
                out.push(v);
            }
        }
    }
    out
}

fn relation_from_atom(sources: &Facts, pred: &'static str, vars: &[&'static str]) -> Relation {
    let tuples: Vec<Vec<i64>> = sources
        .get(pred)
        .map(|s| s.iter().cloned().collect())
        .unwrap_or_default();
    Relation::new(pred, vars.to_vec(), &tuples)
}

fn project_tuple(from_vars: &[&'static str], to_vars: &[&'static str], tuple: &[i64]) -> Vec<i64> {
    let env: HashMap<_, _> = from_vars.iter().zip(tuple.iter().copied()).collect();
    to_vars
        .iter()
        .map(|v| *env.get(v).expect("project var missing"))
        .collect()
}

pub fn eval_rule_with_sources(sources: &Facts, rule: &Rule) -> Facts {
    let join_vars = body_vars(&rule.body);
    let relations: Vec<Relation> = rule
        .body
        .iter()
        .map(|(pred, vs)| relation_from_atom(sources, pred, vs))
        .collect();
    let joined = lftj(relations, join_vars.clone(), ResultMode::Tuples);
    let mut out = HashSet::new();
    for row in joined {
        out.insert(project_tuple(&join_vars, &rule.head_vars, &row));
    }
    let mut m = HashMap::new();
    m.insert(rule.head_pred, out);
    m
}

pub fn eval_rule_delta(facts: &Facts, delta: &Facts, rule: &Rule, delta_idx: usize) -> Facts {
    let mut sources: Facts = HashMap::new();
    for (idx, (pred, _vs)) in rule.body.iter().enumerate() {
        let src = if idx == delta_idx { delta } else { facts };
        let set = src.get(pred).cloned().unwrap_or_default();
        sources.insert(pred, set);
    }
    eval_rule_with_sources(&sources, rule)
}

pub fn semi_naive_step(facts: &Facts, delta: &Facts, rules: &[Rule]) -> Facts {
    let mut derived: HashMap<&'static str, HashSet<Vec<i64>>> = HashMap::new();

    for rule in rules {
        for idx in 0..rule.body.len() {
            let (pred, _) = &rule.body[idx];
            if delta.get(pred).map_or(true, HashSet::is_empty) {
                continue;
            }
            let part = eval_rule_delta(facts, delta, rule, idx);
            for (p, tuples) in part {
                derived.entry(p).or_default().extend(tuples);
            }
        }
    }

    let mut new_delta: Facts = HashMap::new();
    for (pred, tuples) in derived {
        let known = facts.get(pred).cloned().unwrap_or_default();
        let fresh: HashSet<_> = tuples.difference(&known).cloned().collect();
        if !fresh.is_empty() {
            new_delta.insert(pred, fresh);
        }
    }
    new_delta
}

pub fn semi_naive(mut facts: Facts, rules: &[Rule]) -> Facts {
    let mut delta = facts.clone();
    loop {
        let new_delta = semi_naive_step(&facts, &delta, rules);
        if new_delta.values().all(|s| s.is_empty()) {
            return facts;
        }
        for (p, t) in new_delta.iter() {
            facts.entry(*p).or_default().extend(t.iter().cloned());
        }
        delta = new_delta;
    }
}

// ---------------------------------------------------------------------------
// Demo data (matches leapfrog.clj)
// ---------------------------------------------------------------------------

pub fn demo_relations() -> Vec<Relation> {
    vec![
        Relation::new(
            "R",
            vec!["x", "y"],
            &[
                vec![1, 2],
                vec![1, 3],
                vec![2, 2],
                vec![3, 4],
            ],
        ),
        Relation::new(
            "S",
            vec!["y", "z"],
            &[
                vec![2, 5],
                vec![2, 6],
                vec![3, 5],
                vec![4, 9],
            ],
        ),
        Relation::new(
            "T",
            vec!["x", "z"],
            &[
                vec![1, 5],
                vec![1, 7],
                vec![2, 6],
                vec![3, 9],
            ],
        ),
    ]
}

pub fn path_rules() -> Vec<Rule> {
    vec![
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
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    #[test]
    fn lftj_demo_matches_clojure_tuples() {
        let rows = lftj(demo_relations(), vec!["x", "y", "z"], ResultMode::Tuples);
        let s: BTreeSet<_> = rows.into_iter().collect();
        let expected: BTreeSet<_> = [
            vec![1, 2, 5],
            vec![1, 3, 5],
            vec![2, 2, 6],
            vec![3, 4, 9],
        ]
        .into_iter()
        .collect();
        assert_eq!(s, expected);
    }

    #[test]
    fn semi_naive_path() {
        let mut edge = HashSet::new();
        edge.insert(vec![1, 2]);
        edge.insert(vec![2, 3]);
        edge.insert(vec![3, 4]);
        let mut facts = Facts::new();
        facts.insert("edge", edge);
        let out = semi_naive(facts, &path_rules());
        let mut paths: Vec<_> = out.get("path").unwrap().iter().cloned().collect();
        paths.sort();
        assert_eq!(
            paths,
            vec![
                vec![1, 2],
                vec![1, 3],
                vec![1, 4],
                vec![2, 3],
                vec![2, 4],
                vec![3, 4],
            ]
        );
    }
}
