#!/bin/bash -eu
# Script for generating PDF versions of the documentation pages.

CHROME_MAIN_VERSION=$(google-chrome --version | grep -oE " [0-9]+")
NODE_MAIN_VERSION=$(node --version | grep -oE "v[0-9]+" | tr -d 'v')
NODE_SUB_VERSION=$(node --version | grep -oE ".[0-9]+" | tr -d '.')
if (( CHROME_MAIN_VERSION < 59 )); then
  >&2 echo 'Please install Google Chrome 59 or newer or set google-chrome to point to the binary.'
  exit 1
fi
if (( $NODE_MAIN_VERSION < 6 || $NODE_MAIN_VERSION == 6 && $NODE_SUB_VERSION < 4)); then
  >&2 echo 'Please install Google Chrome 59 or newer or set google-chrome to point to the binary.'
  exit 1
fi
WKHTML_OPT='--lowquality --footer-center [page] --margin-top 20mm --margin-bottom 20mm'

echo 'Starting LynxKite...'
cd "$(dirname $0)/../web"
gulp serve &
LYNXKITE_PID=$!
cd -
function kill_grunt {
  echo 'Shutting down LynxKite...'
  kill $LYNXKITE_PID
}
trap kill_grunt EXIT
# Wait until Grunt is up.
tools/wait_for_port.sh 9090
echo # Add new-line after Grunt output.

echo 'Generating User Manual...'
node tools/chrome_print_pdf.js 'http://localhost:9090/pdf-help' 'LynxKite-User-Manual.pdf'

echo 'Generating Admin Manual...'
node tools/chrome_print_pdf.js 'http://localhost:9090/pdf-admin-manual' 'LynxKite-Administrator-Manual.pdf'

echo 'Generating Academy...'
node tools/chrome_print_pdf.js 'http://localhost:9090/pdf-academy' 'LynxKite-Academy.pdf'

echo 'LynxKite documentation generated successfully.'
