/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Merges multiple {@link MediaPeriod} instances.
 */
public final class MergingMediaPeriod implements MediaPeriod, MediaPeriod.Callback {

  private final MediaPeriod[] periods;
  private final IdentityHashMap<SampleStream, Integer> streamPeriodIndices;

  private Callback callback;
  private int pendingChildPrepareCount;
  private long durationUs;
  private TrackGroupArray trackGroups;

  private MediaPeriod[] enabledPeriods;
  private SequenceableLoader sequenceableLoader;

  public MergingMediaPeriod(MediaPeriod... periods) {
    this.periods = periods;
    pendingChildPrepareCount = periods.length;
    streamPeriodIndices = new IdentityHashMap<>();
  }

  @Override
  public void preparePeriod(Callback callback, Allocator allocator, long positionUs) {
    this.callback = callback;
    for (MediaPeriod period : periods) {
      period.preparePeriod(this, allocator, positionUs);
    }
  }

  @Override
  public void maybeThrowPrepareError() throws IOException {
    for (MediaPeriod period : periods) {
      period.maybeThrowPrepareError();
    }
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public long selectTracks(TrackSelection[] selections, boolean[] mayRetainStreamFlags,
      SampleStream[] streams, boolean[] streamResetFlags, long positionUs) {
    // Map each selection and stream onto a child period index.
    int[] streamChildIndices = new int[selections.length];
    int[] selectionChildIndices = new int[selections.length];
    for (int i = 0; i < selections.length; i++) {
      streamChildIndices[i] = streams[i] == null ? -1 : streamPeriodIndices.get(streams[i]);
      selectionChildIndices[i] = -1;
      if (selections[i] != null) {
        TrackGroup trackGroup = selections[i].getTrackGroup();
        for (int j = 0; j < periods.length; j++) {
          if (periods[j].getTrackGroups().indexOf(trackGroup) != -1) {
            selectionChildIndices[i] = j;
            break;
          }
        }
      }
    }
    streamPeriodIndices.clear();
    // Select tracks for each child, copying the resulting streams back into the streams array.
    SampleStream[] childStreams = new SampleStream[selections.length];
    TrackSelection[] childSelections = new TrackSelection[selections.length];
    ArrayList<MediaPeriod> enabledPeriodsList = new ArrayList<>(periods.length);
    for (int i = 0; i < periods.length; i++) {
      for (int j = 0; j < selections.length; j++) {
        childStreams[j] = streamChildIndices[j] == i ? streams[j] : null;
        childSelections[j] = selectionChildIndices[j] == i ? selections[j] : null;
      }
      long selectPositionUs = periods[i].selectTracks(childSelections, mayRetainStreamFlags,
          childStreams, streamResetFlags, positionUs);
      if (i == 0) {
        positionUs = selectPositionUs;
      } else if (selectPositionUs != positionUs) {
        throw new IllegalStateException("Children enabled at different positions");
      }
      boolean periodEnabled = false;
      for (int j = 0; j < selections.length; j++) {
        if (selectionChildIndices[j] == i) {
          streams[j] = childStreams[j];
          if (childStreams[j] != null) {
            periodEnabled = true;
            streamPeriodIndices.put(childStreams[j], i);
          }
        }
      }
      if (periodEnabled) {
        enabledPeriodsList.add(periods[i]);
      }
    }
    // Update the local state.
    enabledPeriods = new MediaPeriod[enabledPeriodsList.size()];
    enabledPeriodsList.toArray(enabledPeriods);
    sequenceableLoader = new CompositeSequenceableLoader(enabledPeriods);
    return positionUs;
  }

  @Override
  public boolean continueLoading(long positionUs) {
    return sequenceableLoader.continueLoading(positionUs);
  }

  @Override
  public long getNextLoadPositionUs() {
    return sequenceableLoader.getNextLoadPositionUs();
  }

  @Override
  public long readDiscontinuity() {
    long positionUs = enabledPeriods[0].readDiscontinuity();
    if (positionUs != C.UNSET_TIME_US) {
      // It must be possible to seek additional periods to the new position.
      for (int i = 1; i < enabledPeriods.length; i++) {
        if (enabledPeriods[i].seekToUs(positionUs) != positionUs) {
          throw new IllegalStateException("Children seeked to different positions");
        }
      }
    }
    // Additional periods are not allowed to report discontinuities.
    for (int i = 1; i < enabledPeriods.length; i++) {
      if (enabledPeriods[i].readDiscontinuity() != C.UNSET_TIME_US) {
        throw new IllegalStateException("Child reported discontinuity");
      }
    }
    return positionUs;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = Long.MAX_VALUE;
    for (MediaPeriod period : enabledPeriods) {
      long rendererBufferedPositionUs = period.getBufferedPositionUs();
      if (rendererBufferedPositionUs != C.END_OF_SOURCE_US) {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.END_OF_SOURCE_US : bufferedPositionUs;
  }

  @Override
  public long seekToUs(long positionUs) {
    positionUs = enabledPeriods[0].seekToUs(positionUs);
    // Additional periods must seek to the same position.
    for (int i = 1; i < enabledPeriods.length; i++) {
      if (enabledPeriods[i].seekToUs(positionUs) != positionUs) {
        throw new IllegalStateException("Children seeked to different positions");
      }
    }
    return positionUs;
  }

  @Override
  public void releasePeriod() {
    for (MediaPeriod period : periods) {
      period.releasePeriod();
    }
  }

  // MediaPeriod.Callback implementation

  @Override
  public void onPeriodPrepared(MediaPeriod ignored) {
    if (--pendingChildPrepareCount > 0) {
      return;
    }
    durationUs = 0;
    int totalTrackGroupCount = 0;
    for (MediaPeriod period : periods) {
      totalTrackGroupCount += period.getTrackGroups().length;
      if (durationUs != C.UNSET_TIME_US) {
        long periodDurationUs = period.getDurationUs();
        durationUs = periodDurationUs == C.UNSET_TIME_US
            ? C.UNSET_TIME_US : Math.max(durationUs, periodDurationUs);
      }
    }
    TrackGroup[] trackGroupArray = new TrackGroup[totalTrackGroupCount];
    int trackGroupIndex = 0;
    for (MediaPeriod period : periods) {
      TrackGroupArray periodTrackGroups = period.getTrackGroups();
      int periodTrackGroupCount = periodTrackGroups.length;
      for (int j = 0; j < periodTrackGroupCount; j++) {
        trackGroupArray[trackGroupIndex++] = periodTrackGroups.get(j);
      }
    }
    trackGroups = new TrackGroupArray(trackGroupArray);
    callback.onPeriodPrepared(this);
  }

  @Override
  public void onContinueLoadingRequested(MediaPeriod ignored) {
    if (trackGroups == null) {
      // Still preparing.
      return;
    }
    callback.onContinueLoadingRequested(this);
  }

}