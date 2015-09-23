'use strict';

var fw = (function UIDescription() {
  var states = {};
  var allStatePreservingTests = {};
  var hasChild = {};

  var mocks = require('../mocks.js');
  mocks.addTo(browser);

  return {
    transitionTest: function(
      previousStateName,  // Name of the state on which this transition should be applied.
      stateName,  // Name of the target state of this transition.
      transitionFunction,  // JS function that goes to this state from prev state.
      checks) {  // Tests confirming we are indeed in this state. Should be very fast stuff only,
                 // like looking at the DOM.
      var testingDone = false;
      hasChild[previousStateName] = true;

      function runStatePreservingTest(currentTest) {
        it(currentTest.name, function() {
          currentTest.runTest();
          // Checking that it was indeed statePreserving.
          checks();
        });
      }

      states[stateName] = {
        reachAndTest: function() {
          if (previousStateName !== undefined) {
            states[previousStateName].reachAndTest();
          }
          describe(stateName, function() {
            it('can be reached', function() {
              transitionFunction();
              checks();
            });
            if (!testingDone) {
              var statePreservingTests = allStatePreservingTests[stateName] || [];
              for (var i = 0; i < statePreservingTests.length; i++) {
                runStatePreservingTest(statePreservingTests[i]);
              }
              testingDone = true;
            }
          });
        },
      };
    },

    // These tests need to preserve the UI state or restore it when they are finished.
    statePreservingTest: function(stateToRunAt, name, body) {
      if (allStatePreservingTests[stateToRunAt] === undefined) {
        allStatePreservingTests[stateToRunAt] = [];
      }
      allStatePreservingTests[stateToRunAt].push({name: name, runTest: body});
    },

    runAll: function() {
      var stateNames = Object.keys(states);
      for (var i = 0; i < stateNames.length; i++) {
        var stateName = stateNames[i];
        var state = states[stateName];
        // We only need to directly trigger testing for leaf nodes of the dependency trees as
        // states with children will be triggered by their children.
        if (!hasChild[stateName]) {
          state.reachAndTest();
        }
      }
    },
  };
})();

require('./example-graph-basics.js')(fw);
require('./download-test.js')(fw);
require('./upload-test.js')(fw);
require('./filter-tests.js')(fw);
require('./segmentation-opens.js')(fw);
require('./help-popups.js')(fw);

fw.runAll();
