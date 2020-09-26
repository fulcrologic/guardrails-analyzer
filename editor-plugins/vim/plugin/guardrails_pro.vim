function! guardrails_pro#run_check()
  call ale#lsp_linter#SendRequest(bufnr('%'), "guardrails",
        \ [0, 'workspace/executeCommand'
        \ , {'command': 'check!', 'arguments': expand('%:p')}])
endfunction
