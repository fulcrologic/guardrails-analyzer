function! guardrails_pro#check_current_file()
  call ale#lsp_linter#SendRequest(bufnr('%'), "guardrails",
        \ [0, 'workspace/executeCommand'
        \ , {'command'  : 'check-file!'
        \   ,'arguments': [expand('%:p'), {}]}])
endfunction

function! guardrails_pro#refresh_and_check_current_file()
  call ale#lsp_linter#SendRequest(bufnr('%'), "guardrails",
        \ [0, 'workspace/executeCommand'
        \ , {'command'  : 'check-file!'
        \   ,'arguments': [expand('%:p'), {'refresh?': v:true}]}])
endfunction

function! guardrails_pro#check_root_form()
  call ale#lsp_linter#SendRequest(bufnr('%'), "guardrails",
        \ [0, 'workspace/executeCommand'
        \ , {'command'  : 'check-root-form!'
        \   ,'arguments': [expand('%:p'), line('.'), {}]}])
endfunction

function! guardrails_pro#refresh_and_check_root_form()
  call ale#lsp_linter#SendRequest(bufnr('%'), "guardrails",
        \ [0, 'workspace/executeCommand'
        \ , {'command'  : 'check-root-form!'
        \   ,'arguments': [expand('%:p'), line('.'), {'refresh?': v:true}]}])
endfunction
