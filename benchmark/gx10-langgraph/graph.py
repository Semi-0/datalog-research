"""Visible delegation stages. Infrastructure inspection never starts inference."""
import importlib.metadata
import operator
import re
from typing import Annotated, TypedDict

from langgraph.graph import StateGraph, START, END


class RunState(TypedDict, total=False):
    run_id: str
    mode: str
    stage: str
    events: Annotated[list[dict], operator.add]
    failure: str


def admission(state):
    identity = state.get('run_id', '')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', identity):
        raise ValueError('A bounded run_id is required')
    mode = state.get('mode', 'infrastructure')
    if mode not in ('infrastructure', 'smoke', 'distinct'):
        raise ValueError('Only infrastructure, smoke and Distinct are authorized')
    versions = {name: importlib.metadata.version(name)
                for name in ('langgraph', 'langgraph-api', 'langgraph-cli')}
    if mode == 'infrastructure':
        return {'stage': 'infrastructure_inspected', 'events': [
            {'stage': 'Admission', 'models_loaded': False, 'versions': versions}]}
    raise RuntimeError('setup_declined: live assessment is not installed. Required trusted-tool confinement, '
                       'GX10 lifecycle adapter, Harbor Distinct adapter and recovery validation remain incomplete.')


def action(stage):
    def run(state):
        raise RuntimeError('setup_declined: live effect adapter is unavailable for ' + stage)
    return run


def after_admission(state):
    if state.get('mode', 'infrastructure') == 'infrastructure':
        return END
    return 'Flash plans'


def build_graph():
    workflow = StateGraph(RunState)
    workflow.add_node('Admission', admission)
    stages = ['Flash plans', 'Save plan and unload Flash', 'Load shared Qwen',
              'Implementation worker', 'Test worker', 'Join and collect patches',
              'Stop workers and unload Qwen', 'Reload Flash and integrate',
              'Harbor independent verification', 'Save result and cleanup']
    for stage in stages:
        workflow.add_node(stage, action(stage))
    workflow.add_edge(START, 'Admission')
    workflow.add_conditional_edges('Admission', after_admission, [END, 'Flash plans'])
    workflow.add_edge('Flash plans', 'Save plan and unload Flash')
    workflow.add_edge('Save plan and unload Flash', 'Load shared Qwen')
    workflow.add_edge('Load shared Qwen', 'Implementation worker')
    workflow.add_edge('Load shared Qwen', 'Test worker')
    workflow.add_edge(['Implementation worker', 'Test worker'], 'Join and collect patches')
    workflow.add_edge('Join and collect patches', 'Stop workers and unload Qwen')
    workflow.add_edge('Stop workers and unload Qwen', 'Reload Flash and integrate')
    workflow.add_edge('Reload Flash and integrate', 'Harbor independent verification')
    workflow.add_edge('Harbor independent verification', 'Save result and cleanup')
    workflow.add_edge('Save result and cleanup', END)
    return workflow.compile()


graph = build_graph()
