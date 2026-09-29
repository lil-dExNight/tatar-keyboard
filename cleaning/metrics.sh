#!/bin/bash
# Cleanup-plan metrics (Appendix C of cleaning/CLEANUP-PLAN.md)
M=app/src/main/java; T="app/src/test app/src/androidTest baselineprofile/src"; S="scripts research/corpus"
c(){ grep -rEo "$1" $2 2>/dev/null | wc -l | tr -d ' '; }
cy='[А-Яа-яЁёӘәӨөҮүҖҗҢңҺһ]'
echo "doc_links_main=$(c 'docs/[A-Za-z0-9/_.-]+\.md' "$M")"
echo "doc_links_tests=$(c 'docs/[A-Za-z0-9/_.-]+\.md' "$T")"
echo "doc_links_scripts=$(c 'docs/[A-Za-z0-9/_.-]+\.md' "$S")"
echo "doc_links_res=$(c 'docs/[A-Za-z0-9/_.-]+\.md' app/src/main/res)"
echo "dated_main=$(c '20[0-9]{2}-[01][0-9]-[0-3][0-9]' "$M")"
echo "dated_tests=$(c '20[0-9]{2}-[01][0-9]-[0-3][0-9]' "$T")"
echo "dated_scripts=$(c '20[0-9]{2}-[01][0-9]-[0-3][0-9]' "$S")"
echo "fail_closed=$(grep -rniEo 'fail-closed' $M $T $S docs *.md 2>/dev/null | wc -l | tr -d ' ')"
echo "docs_top_md=$(ls docs/*.md 2>/dev/null | wc -l | tr -d ' ')"
echo "docs_tracked=$(git ls-files docs | wc -l | tr -d ' ')"
echo "docs_non_md=$(git ls-files docs | grep -vcE '\.md$')"
echo "cyr_comments_main=$(grep -rnE "^\s*(//|\*|/\*).*$cy" $M | wc -l | tr -d ' ')"
echo "cyr_comments_tests=$(grep -rnE "^\s*(//|\*|/\*).*$cy" $T 2>/dev/null | wc -l | tr -d ' ')"
echo "cyr_comments_res=$(grep -rnE "<!--.*$cy|^\s[^<]*$cy.*-->" app/src/main/res | grep -v '<string' | wc -l | tr -d ' ')"
echo "cyr_hash_scripts=$(grep -rnE "^\s*#.*$cy" scripts research/corpus tests .github 2>/dev/null | wc -l | tr -d ' ')"
echo "md_with_cyr_excl_readme=$(git ls-files '*.md' | grep -v '^README.md$' | grep -v '^cleaning/' | while read f; do grep -qE "$cy" "$f" && echo "$f"; done | wc -l | tr -d ' ')"
echo "missing_doc_links=$(grep -rhEo 'docs/[A-Za-z0-9/_.-]+\.md' app/src scripts research/corpus baselineprofile/src 2>/dev/null | sort -u | while read p; do [ -e "$p" ] || echo "$p"; done | wc -l | tr -d ' ')"
wc -l HANDOFF.md CHANGELOG.md docs/PUBLISH-CHECKLIST.md docs/README.md 2>/dev/null | sed 's/^/lines /'
