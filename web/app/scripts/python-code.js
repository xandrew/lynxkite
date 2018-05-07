// The modal dialog for generated Python code
'use strict';

angular.module('biggraph').controller('PythonCodeCtrl', function($scope, $modalInstance, code) {
  $scope.code = code;

  $scope.selectAll = function() {
    let text = angular.element('#python-code')[0];
    text.focus();
    text.select();
  };

  $scope.close = function() {
    $modalInstance.dismiss('close');
  };
});
