export function execution(messages, collection) {
  const assistant = messages.filter((message) => message.role === 'assistant');
  const generated = assistant.reduce((sum, message) => sum + (message.usage?.output ?? 0), 0);
  const errors = assistant.filter((message) => ['error', 'aborted', 'length'].includes(message.stopReason) || message.errorMessage);
  let outcome = 'incomplete_response';
  for (const message of errors) {
    if (message.stopReason === 'aborted') {
      outcome = 'interrupted';
      break;
    }
    if (/budget_exhausted/i.test(message.errorMessage ?? '')) {
      outcome = 'budget_exhausted';
      break;
    }
    if (/401|403|unauthorized|invalid api key/i.test(message.errorMessage ?? '')) {
      outcome = 'authentication_error';
      break;
    }
    if (message.stopReason === 'error' || message.errorMessage) {
      outcome = 'provider_error';
      break;
    }
  }
  const terminal = assistant.at(-1);
  if (errors.length === 0 && collection.details?.status === 'completed'
      && collection.details.head?.includes('lifecycle complete')
      && terminal?.stopReason === 'stop' && generated > 0) {
    outcome = 'completed';
  }
  return { outcome, assistant_messages: assistant.length, generated_tokens: generated,
    assistant_errors: errors, plugin: collection };
}
