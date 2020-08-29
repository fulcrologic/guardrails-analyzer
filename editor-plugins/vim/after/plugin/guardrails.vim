call ale#linter#Define('clojure', {
            \   'name': 'guardrails',
            \   'executable': 'guardrails',
            \   'command': 'guardrails %s vim',
            \   'callback': {_, lines -> json_decode(lines)}
            \})
