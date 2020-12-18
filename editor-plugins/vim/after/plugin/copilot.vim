function! s:FindProjectRoot(b)
  let cfg = findfile("guardrails.edn", bufname("#".a:b.":p").';')
  return l:cfg == '' ? l:cfg : fnamemodify(l:cfg, ":h")
endfunction

function! s:FindLspServerPort(b)
  let port_file = $HOME . '/.copilot/lsp-server.port'
  if filereadable(l:port_file)
    let l:port = readfile(l:port_file)[0]
    return 'localhost:' . l:port
  endif
endfunction

call ale#linter#Define('clojure', {
      \   'name': 'copilot',
      \   'lsp': 'socket',
      \   'address': function('s:FindLspServerPort'),
      \   'project_root': function('s:FindProjectRoot'),
      \})
