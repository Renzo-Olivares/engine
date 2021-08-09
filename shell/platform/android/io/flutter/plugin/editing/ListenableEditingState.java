// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import io.flutter.Log;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import java.util.ArrayList;

/// The current editing state (text, selection range, composing range) the text input plugin holds.
///
/// As the name implies, this class also notifies its listeners when the editing state changes. When
/// there're ongoing batch edits, change notifications will be deferred until all batch edits end
/// (i.e. when the outermost batch edit ends). Listeners added during a batch edit will always be
/// notified when all batch edits end, even if there's no real change.
///
/// Adding/removing listeners or changing the editing state in a didChangeEditingState callback may
/// cause unexpected behavior.
//
// Currently this class does not notify its listeners on spans-only changes (e.g.,
// Selection.setSelection). Wrap them in a batch edit to trigger a change notification.
class ListenableEditingState extends SpannableStringBuilder {
  interface EditingStateWatcher {
    // Changing the editing state in a didChangeEditingState callback may cause unexpected
    // behavior.
    void didChangeEditingState(
        boolean textChanged, boolean selectionChanged, boolean composingRegionChanged);
  }

  private static final String TAG = "ListenableEditingState";

  private int mBatchEditNestDepth = 0;
  // We don't support adding/removing listeners, or changing the editing state in a listener
  // callback for now.
  private int mChangeNotificationDepth = 0;
  private ArrayList<EditingStateWatcher> mListeners = new ArrayList<>();
  private ArrayList<EditingStateWatcher> mPendingListeners = new ArrayList<>();

  private String mToStringCache;

  private String mTextWhenBeginBatchEdit;
  private int mSelectionStartWhenBeginBatchEdit;
  private int mSelectionEndWhenBeginBatchEdit;
  private int mComposingStartWhenBeginBatchEdit;
  private int mComposingEndWhenBeginBatchEdit;

  private BaseInputConnection mDummyConnection;

  // Fields for delta.
  private String oldText;
  private String newText;
  private String deltaType;
  private int modifiedRangeStart;
  private int modifiedRangeExtent;
  private int newRangeStart;
  private int newRangeExtent;

  public String getOldText() {
    return oldText;
  }

  public String getNewText() {
    return newText;
  }

  public String getDeltaType() {
    return deltaType;
  }

  public int getModifiedRangeStart() {
    return modifiedRangeStart;
  }

  public int getModifiedRangeExtent() {
    return modifiedRangeExtent;
  }

  public int getNewRangeStart() {
    return newRangeStart;
  }

  public int getNewRangeExtent() {
    return newRangeExtent;
  }

  private void setDeltas(
      String oldTxt,
      String newTxt,
      String type,
      int modStart,
      int modExtent,
      int newStart,
      int newExtent) {
    oldText = oldTxt;
    newText = newTxt;
    modifiedRangeStart = modStart;
    modifiedRangeExtent = modExtent;
    newRangeStart = newStart;
    newRangeExtent = newExtent;
    deltaType = type;
  }

  // The View is only used for creating a dummy BaseInputConnection for setComposingRegion. The View
  // needs to have a non-null Context.
  public ListenableEditingState(TextInputChannel.TextEditState initalState, View view) {
    super();
    if (initalState != null) {
      setEditingState(initalState);
    }

    Editable self = this;
    mDummyConnection =
        new BaseInputConnection(view, true) {
          @Override
          public Editable getEditable() {
            return self;
          }
        };
  }

  /// Starts a new batch edit during which change notifications will be put on hold until all batch
  /// edits end.
  ///
  /// Batch edits nest.
  public void beginBatchEdit() {
    mBatchEditNestDepth++;
    if (mChangeNotificationDepth > 0) {
      Log.e(TAG, "editing state should not be changed in a listener callback");
    }
    if (mBatchEditNestDepth == 1 && !mListeners.isEmpty()) {
      mTextWhenBeginBatchEdit = toString();
      mSelectionStartWhenBeginBatchEdit = getSelectionStart();
      mSelectionEndWhenBeginBatchEdit = getSelectionEnd();
      mComposingStartWhenBeginBatchEdit = getComposingStart();
      mComposingEndWhenBeginBatchEdit = getComposingEnd();
    }
  }

  /// Ends the current batch edit and flush pending change notifications if the current batch edit
  /// is not nested (i.e. it is the last ongoing batch edit).
  public void endBatchEdit() {
    if (mBatchEditNestDepth == 0) {
      Log.e(TAG, "endBatchEdit called without a matching beginBatchEdit");
      return;
    }
    if (mBatchEditNestDepth == 1) {
      for (final EditingStateWatcher listener : mPendingListeners) {
        notifyListener(listener, true, true, true);
      }

      if (!mListeners.isEmpty()) {
        Log.v(TAG, "didFinishBatchEdit with " + String.valueOf(mListeners.size()) + " listener(s)");
        final boolean textChanged = !toString().equals(mTextWhenBeginBatchEdit);
        final boolean selectionChanged =
            mSelectionStartWhenBeginBatchEdit != getSelectionStart()
                || mSelectionEndWhenBeginBatchEdit != getSelectionEnd();
        final boolean composingRegionChanged =
            mComposingStartWhenBeginBatchEdit != getComposingStart()
                || mComposingEndWhenBeginBatchEdit != getComposingEnd();

        notifyListenersIfNeeded(textChanged, selectionChanged, composingRegionChanged);
      }
    }

    mListeners.addAll(mPendingListeners);
    mPendingListeners.clear();
    mBatchEditNestDepth--;
  }

  /// Update the composing region of the current editing state.
  ///
  /// If the range is invalid or empty, the current composing region will be removed.
  public void setComposingRange(int composingStart, int composingEnd) {
    if (composingStart < 0 || composingStart >= composingEnd) {
      BaseInputConnection.removeComposingSpans(this);
    } else {
      mDummyConnection.setComposingRegion(composingStart, composingEnd);
    }
  }

  /// Called when the framework sends updates to the text input plugin.
  ///
  /// This method will also update the composing region if it has changed.
  public void setEditingState(TextInputChannel.TextEditState newState) {
    beginBatchEdit();
    Log.e("DELTAS", "setEditingState updating from FRAMEWORK");
    replace(0, length(), newState.text);

    if (newState.hasSelection()) {
      Selection.setSelection(this, newState.selectionStart, newState.selectionEnd);
    } else {
      Selection.removeSelection(this);
    }
    setComposingRange(newState.composingStart, newState.composingEnd);
    endBatchEdit();
  }

  public void addEditingStateListener(EditingStateWatcher listener) {
    if (mChangeNotificationDepth > 0) {
      Log.e(TAG, "adding a listener " + listener.toString() + " in a listener callback");
    }
    // It is possible for a listener to get added during a batch edit. When that happens we always
    // notify the new listeners.
    // This does not check if the listener is already in the list of existing listeners.
    if (mBatchEditNestDepth > 0) {
      Log.w(TAG, "a listener was added to EditingState while a batch edit was in progress");
      mPendingListeners.add(listener);
    } else {
      mListeners.add(listener);
    }
  }

  public void removeEditingStateListener(EditingStateWatcher listener) {
    if (mChangeNotificationDepth > 0) {
      Log.e(TAG, "removing a listener " + listener.toString() + " in a listener callback");
    }
    mListeners.remove(listener);
    if (mBatchEditNestDepth > 0) {
      mPendingListeners.remove(listener);
    }
  }

  @Override
  public SpannableStringBuilder append(char text) {
    Log.e("DELTAS", "insert #1 is called");
    return append(String.valueOf(text));
  }

  @Override
  public SpannableStringBuilder append(CharSequence text, Object what, int flags) {
    Log.e("DELTAS", "append #2 is called");
    return super.append(text, what, flags);
  }

  @Override
  public SpannableStringBuilder append(CharSequence text, int start, int end) {
    Log.e("DELTAS", "append #3 is called");
    return replace(text.length(), text.length(), text, start, end);
  }

  @Override
  public SpannableStringBuilder append(CharSequence text) {
    Log.e("DELTAS", "append #4 is called");
    return replace(text.length(), text.length(), text, 0, text.length());
  }

  @Override
  public SpannableStringBuilder insert(int where, CharSequence tb) {
    Log.e("DELTAS", "insert #1 is called");
    return replace(where, where, tb, 0, tb.length());
  }

  @Override
  public SpannableStringBuilder insert(int where, CharSequence tb, int start, int end) {
    Log.e("DELTAS", "insert #2 is called");
    return replace(where, where, tb, start, end);
  }

  @Override
  public SpannableStringBuilder delete(int start, int end) {
    Log.e("DELTAS", "delete is called");
    return replace(start, end, "", 0, 0);
  }

  @Override
  public SpannableStringBuilder replace(
      int start, int end, CharSequence tb, int tbstart, int tbend) {

    if (mChangeNotificationDepth > 0) {
      Log.e(TAG, "editing state should not be changed in a listener callback");
    }

    Log.e(
        "DELTAS",
        "replace(" + start + ", " + end + ", " + tb + ", " + tbstart + ", " + tbend + ")");

    // Length of text currently being composed shortens by more than one.
    final boolean isDeletionGreaterThanOne = end - (start + tbend) > 1;
    final boolean previousComposingReplacedByShorter =
        end - start > tbend - tbstart && isDeletionGreaterThanOne;

    // Conditions based on parameters from SpannableStringBuilder.delete().
    final boolean isCalledFromDelete = tb == "" && tbstart == 0 && tbstart == tbend;
    // TODO: Explain conditions.
    final boolean isDeletingInsideComposingRegion =
        !previousComposingReplacedByShorter && start != end && end > start + tbend;

    // To consider the cases when autocorrect increases the lenght of the text being composed by
    // one, but changes more than one character.
    final boolean isOriginalComposingRegionTextChanged =
        (isCalledFromDelete
                || isDeletingInsideComposingRegion
                || previousComposingReplacedByShorter)
            || !toString()
                .subSequence(start, end)
                .equals(tb.toString().subSequence(tbstart, end - start));

    final boolean isInsertionGreaterThanOne = tbend - (end - start) > 1;
    final boolean isInsertionAtLeastOne = tbend - (end - start) > 0;
    final boolean isInsertionOne = tbend - (end - start) == 1;
    // Length of text currently being composed increases by more than one or text changed.
    final boolean isInsertionAtLeastOneAndComposingTextChanged =
        isOriginalComposingRegionTextChanged && isInsertionAtLeastOne;
    // Cases:
    // Your finishing the word by appending new content
    // Your finishing the word by correcting previous content and appending new content

    final boolean previousComposingReplacedByLonger =
        end - start < tbend - tbstart && isInsertionAtLeastOneAndComposingTextChanged;
    final boolean previousComposingReplacedBySame = end - start == tbend - tbstart;
    // Log.e("DELTAS", "edited word " + toString());
    // if (isCalledFromDelete || isDeletingInsideComposingRegion
    //     || previousComposingReplacedByShorter) {
    //   Log.e(
    //       "DELTAS",
    //       "isOriginalComposingRegionTextChanged delete called"
    //           + isOriginalComposingRegionTextChanged);
    // } else {
    //   Log.e(
    //       "DELTAS",
    //       "isOriginalComposingRegionTextChanged "
    //           + toString().subSequence(start, end)
    //           + " "
    //           + toString().subSequence(start, end).length()
    //           + " == "
    //           + tb.subSequence(tbstart, end - start)
    //           + " "
    //           + tb.subSequence(tbstart, end - start).length()
    //           + " = "
    //           + isOriginalComposingRegionTextChanged);
    // }

    final boolean insertingOutsideComposingRegion = start == end;
    final boolean insertingInsideComposingRegion =
        !insertingOutsideComposingRegion
            && !isOriginalComposingRegionTextChanged
            && isInsertionAtLeastOne
            && start + tbend > end;

    if (isCalledFromDelete || isDeletingInsideComposingRegion) { // Deletion.
      Log.e("DELTAS", "There has been a deletion");
      if (isCalledFromDelete) {
        Log.e("DELTAS", "isCalledFromDelete");
      } else if (isDeletingInsideComposingRegion) {
        Log.e("DELTAS", "isDeletingInsideComposingRegion");
      }
      Log.e(
          "DELTAS",
          "character : "
              + toString().subSequence(start + tbend, end)
              + " was removed at position: "
              + (start + tbend)
              + " to "
              + end);
      setDeltas(
          toString().subSequence(start + tbend, end).toString(),
          "",
          "DELETION",
          start,
          start + tbend,
          start,
          start);
    } else if ((previousComposingReplacedByShorter
            || previousComposingReplacedByLonger
            || previousComposingReplacedBySame)
        && !(insertingOutsideComposingRegion || insertingInsideComposingRegion)) { // Replacement.
      Log.e("DELTAS", "There has been a replacement");
      if (previousComposingReplacedByShorter) {
        // When auto correct replaces with a correction of shorter length. Is this case possible?
        // In english the auto correct doesn't seem to suggest a word shorter than the one in the
        // composing region. When a selection is replaced by a single character.
        Log.e("DELTAS", "previousComposingRegionReplacedByShorter");
      } else if (previousComposingReplacedByLonger) {
        // When auto correct replaces with a correction of greater length.
        // Currently this case is not hit, it registers as an insertion inside the composing region.
        Log.e("DELTAS", "previousComposingRegionReplacedByLonger");
      } else if (previousComposingReplacedBySame) {
        // When auto correct replaces a word with a correction of the same length.
        Log.e("DELTAS", "previousComposingReplacedBySame");
      }
      Log.e(
          "DELTAS",
          "sequence: "
              + toString().subSequence(start, end)
              + " at position start: "
              + start
              + " to end:"
              + end
              + " is replaced by "
              + tb.subSequence(tbstart, tbend));
      setDeltas(
          toString().subSequence(start, end).toString(),
          tb.subSequence(tbstart, tbend).toString(),
          "REPLACEMENT", start, end,
          start + tbstart,
          start + tbend);
    } else if (insertingOutsideComposingRegion || insertingInsideComposingRegion) { // Insertion.
      if (insertingInsideComposingRegion) {
        Log.e("DELTAS", "insertingInsideComposingRegion");
      } else if (insertingOutsideComposingRegion) {
        Log.e("DELTAS", "insertingOutsideComposingRegion");
      }
      setDeltas(
          toString().subSequence(start, end).toString(),
          tb.subSequence(end - start, tbend).toString(),
          "INSERTION",
          start,
          end,
          end,
          start + tbend);
      Log.e(
          "DELTAS",
          "inserting: "
              + tb.subSequence(end - start, tbend)
              + " at position "
              + end
              + " to "
              + (start + tbend));
    }

    boolean textChanged = end - start != tbend - tbstart;
    for (int i = 0; i < end - start && !textChanged; i++) {
      textChanged |= charAt(start + i) != tb.charAt(tbstart + i);
    }
    if (textChanged) {
      mToStringCache = null;
    }

    final int selectionStart = getSelectionStart();
    final int selectionEnd = getSelectionEnd();
    final int composingStart = getComposingStart();
    final int composingEnd = getComposingEnd();

    final SpannableStringBuilder editable = super.replace(start, end, tb, tbstart, tbend);
    if (mBatchEditNestDepth > 0) {
      return editable;
    }

    final boolean selectionChanged =
        getSelectionStart() != selectionStart || getSelectionEnd() != selectionEnd;
    final boolean composingRegionChanged =
        getComposingStart() != composingStart || getComposingEnd() != composingEnd;
    notifyListenersIfNeeded(textChanged, selectionChanged, composingRegionChanged);
    return editable;
  }

  private void notifyListener(
      EditingStateWatcher listener,
      boolean textChanged,
      boolean selectionChanged,
      boolean composingChanged) {
    mChangeNotificationDepth++;
    listener.didChangeEditingState(textChanged, selectionChanged, composingChanged);
    mChangeNotificationDepth--;
  }

  private void notifyListenersIfNeeded(
      boolean textChanged, boolean selectionChanged, boolean composingChanged) {
    if (textChanged || selectionChanged || composingChanged) {
      for (final EditingStateWatcher listener : mListeners) {
        notifyListener(listener, textChanged, selectionChanged, composingChanged);
      }
    }
  }

  public final int getSelectionStart() {
    return Selection.getSelectionStart(this);
  }

  public final int getSelectionEnd() {
    return Selection.getSelectionEnd(this);
  }

  public final int getComposingStart() {
    return BaseInputConnection.getComposingSpanStart(this);
  }

  public final int getComposingEnd() {
    return BaseInputConnection.getComposingSpanEnd(this);
  }

  @Override
  public String toString() {
    return mToStringCache != null ? mToStringCache : (mToStringCache = super.toString());
  }
}
