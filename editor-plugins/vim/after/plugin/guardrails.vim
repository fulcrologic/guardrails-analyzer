call ale#linter#Define('clojure', {
            \   'name': 'guardrails',
            \   'executable': 'guardrails',
            \   'command': 'guardrails %t',
            \   'callback': {_, lines -> map(lines, 'json_decode(v:val)')}
            \})
