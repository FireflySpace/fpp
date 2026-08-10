#!/bin/sh

# Build the escape sequences with real ESC bytes via printf so plain echo
# emits them verbatim regardless of whether /bin/sh's echo interprets
# backslash escapes (dash does, bash does not), keeping test-runner color
# consistent across the macOS and manylinux Scala Native CI builds.
esc=`printf '\033'`
export NO_COLOR="${esc}[0m"
export BOLD="${esc}[1m"
export RED="${esc}[31m"
export GREEN="${esc}[32m"

echo_green()
{
  echo "${GREEN}${BOLD}$@${NO_COLOR}"
}

echo_red()
{
  echo "${RED}${BOLD}$@${NO_COLOR}"
}

# Remove local path prefix
remove_path_prefix()
{
  if test -z "$LOCAL_PATH_PREFIX"
  then
    export LOCAL_PATH_PREFIX=`cd ../../../..; echo $PWD`
  fi
  sed "s;$LOCAL_PATH_PREFIX;[ local path prefix ];g"
}

# Remove local author
remove_author()
{
  sed 's;^// \\author .*;// \\author [user name];'
}

# Run a test
run()
{
  printf '%-74s' $1
  $@
  status=$?
  if test $status -eq 0
  then
    echo_green PASSED
  else
    echo_red FAILED
  fi
  return $status
}

# Run a test suite
run_suite()
{

  tests=$@

  num_passed=0
  num_failed=0

  {
    for t in $tests
    do
      if run $t
      then
        num_passed=`expr $num_passed + 1`
      else
        num_failed=`expr $num_failed + 1`
      fi
    done

    printf "$num_passed passed"
    if test $num_failed -gt 0
    then
      printf ", $num_failed failed"
    fi
    echo
    echo $num_failed > num_failed.txt
  } 2>&1 | tee test-output.txt

  exit `cat num_failed.txt`

}

remove_fpp_version()
{
  sed -E 's/("fppVersion"[[:space:]]*:[[:space:]]*")[^"]+(")/\1[ fpp version ]\2/'
}
