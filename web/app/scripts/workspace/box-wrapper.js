'use strict';

// Creates a box object for a workspace.
// This object wraps the actual box data representation and
// provides methods to interact with the GUI, e.g. mouse events
// and values to bind with Angular to SVG elements.
// The wrapped box data representation has two parts:
// - metadata describes the type of operation of the box.
// - instance describes the operation parameter values,
//   coordinates on the workspace and everything related to this
//   box instance that have to be saved.

angular.module('biggraph').factory('BoxWrapper', function(PlugWrapper) {
  function getCommentLines(metadata, instance) {
    var comment;
    if (metadata.operationID === 'Add comment') {
      comment = instance.parameters.comment;
    } else if (metadata.operationID === 'Anchor') {
      comment = instance.parameters.description;
    }
    return comment ? comment.split('\n') : [];
  }

  function BoxWrapper(metadata, instance) {
    this.metadata = metadata;
    this.instance = instance,
    this.inputs = [];
    this.outputs = [];
    this.outputMap = {};
    this.width = 200;
    this.height = 40;
    this.commentLines = getCommentLines(metadata, instance);
    this.isMoved = false;

    var i;
    for (i = 0; i < metadata.inputs.length; ++i) {
      this.inputs.push(new PlugWrapper(metadata.inputs[i], i, 'inputs', this));
    }
    for (i = 0; i < metadata.outputs.length; ++i) {
      var plug = new PlugWrapper(metadata.outputs[i], i, 'outputs', this);
      this.outputs.push(plug);
      this.outputMap[plug.id] = plug;
    }
  }

  BoxWrapper.prototype = {
    cx: function() { return this.instance.x + this.width / 2; },
    cy: function() { return this.instance.y + this.height / 2; },
    mainPosTransform: function() {
      return 'translate(' + this.instance.x + ', ' + this.instance.y + ')';
    },
    onMouseMove: function(event) {
      this.isMoved = true;
      this.instance.x = event.logicalX + this.xOffset;
      this.instance.y = event.logicalY + this.yOffset;
    },
    onMouseDown: function(event) {
      this.xOffset = this.instance.x - event.logicalX;
      this.yOffset = this.instance.y - event.logicalY;
    },
  };

  return BoxWrapper;
});
