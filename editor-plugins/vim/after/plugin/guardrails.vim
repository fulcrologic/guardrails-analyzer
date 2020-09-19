function! s:FindProjectRoot(b)
  " TASK: look up directory for guardrails.edn
  " TODO: return an empty string to not check
  return '/Users/pancia/projects/work/guardrails-pro'
endfunction

function! s:FindProjectAddress(b)
  " TASK: look up directory for .guardrails-pro-port
  return 'localhost:9999'
endfunction

call ale#linter#Define('clojure', {
      \   'name': 'guardrails',
      \   'lsp': 'socket',
      \   'address': function('s:FindProjectAddress'),
      \   'project_root': function('s:FindProjectRoot'),
      \})
