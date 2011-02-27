/*
 * Copyright 2010 Mario Zechner (contact@badlogicgames.com), Nathan Sweet (admin@esotericsoftware.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.badlogic.gdx.backends.openal;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL11;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import static org.lwjgl.openal.AL10.*;

/**
 * @author Nathan Sweet
 */
public abstract class OpenALMusic implements Music {
	static private final int bufferSize = 4096 * 10;
	static private final int bufferCount = 3;
	static private final int bytesPerSample = 2;
	static private final byte[] tempBytes = new byte[bufferSize];
	static private final ByteBuffer tempBuffer = BufferUtils.createByteBuffer(bufferSize);

	private final OpenALAudio audio;
	private IntBuffer buffers;
	private int streamID = -1;
	private int format, sampleRate;
	private boolean isLooping, isPlaying;
	private float volume = 1;
	private float renderedSeconds, secondsPerBuffer;

	protected final FileHandle file;

	public OpenALMusic (OpenALAudio audio, FileHandle file) {
		this.audio = audio;
		this.file = file;

		buffers = BufferUtils.createIntBuffer(bufferCount);
		alGenBuffers(buffers);
		if (alGetError() != AL_NO_ERROR) throw new GdxRuntimeException("Unabe to allocate audio buffers.");

		audio.music.add(this);
	}

	protected void setup (int channels, int sampleRate) {
		this.format = channels > 1 ? AL_FORMAT_STEREO16 : AL_FORMAT_MONO16;
		this.sampleRate = sampleRate;
		secondsPerBuffer = (float)bufferSize / bytesPerSample / channels / sampleRate;
	}

	public void play () {
		if (streamID == -1) {
			streamID = audio.obtainStream(true);
			if (streamID == -1) return;
			alSourceStop(streamID);
			alSourcei(streamID, AL_BUFFER, 0);
			alSourcei(streamID, AL_LOOPING, AL_FALSE);
			alSourcef(streamID, AL_GAIN, volume);
			for (int i = 0; i < bufferCount; i++)
				fill(buffers.get(i));
			alSourceQueueBuffers(streamID, buffers);
			if (alGetError() != AL_NO_ERROR) {
				stop();
				return;
			}
		}
		alSourcePlay(streamID);
		isPlaying = true;
	}

	public void stop () {
		if (streamID == -1) return;
		reset();
		alSourceStop(streamID);
		alSourcei(streamID, AL_BUFFER, 0);
		audio.freeStream(streamID);
		streamID = -1;
		renderedSeconds = 0;
		isPlaying = false;
	}

	public void pause () {
		if (streamID != -1) alSourcePause(streamID);
		isPlaying = false;
	}

	public boolean isPlaying () {
		if (streamID == -1) return false;
		return isPlaying;
	}

	public void setLooping (boolean isLooping) {
		this.isLooping = isLooping;
	}

	public boolean isLooping () {
		return isLooping;
	}

	public void setVolume (float volume) {
		this.volume = volume;
		if (streamID != -1) alSourcef(streamID, AL_GAIN, volume);
	}

	public float getPosition () {
		if (streamID == -1) return 0;
		return renderedSeconds + alGetSourcef(streamID, AL11.AL_SEC_OFFSET);
	}

	/**
	 * Fills as much of the buffer as possible and returns the number of bytes filled. Returns <= 0 to indicate the end of the
	 * stream.
	 */
	abstract protected int read (byte[] buffer);

	/**
	 * Resets the stream to the beginning.
	 */
	abstract protected void reset ();

	public void update () {
		if (streamID == -1) return;
		// A buffer underflow will cause the source to stop.
		if (isPlaying && alGetSourcei(streamID, AL_SOURCE_STATE) != AL_PLAYING) alSourcePlay(streamID);
		int buffers = alGetSourcei(streamID, AL_BUFFERS_PROCESSED);
		while (buffers-- > 0) {
			int bufferID = alSourceUnqueueBuffers(streamID);
			if (bufferID == AL_INVALID_VALUE) break;
			renderedSeconds += secondsPerBuffer;
			if (!fill(bufferID)) return;
			alSourceQueueBuffers(streamID, bufferID);
		}
	}

	private boolean fill (int bufferID) {
		tempBuffer.clear();
		int length = read(tempBytes);
		if (length <= 0) {
			if (isLooping) {
				reset();
				renderedSeconds = 0;
				length = read(tempBytes);
				if (length <= 0) return false;
			} else {
				stop();
				return false;
			}
		}
		tempBuffer.put(tempBytes, 0, length).flip();
		alBufferData(bufferID, format, tempBuffer, sampleRate);
		return true;
	}

	public void dispose () {
		if (buffers == null) return;
		if (streamID != -1) {
			reset();
			audio.music.removeValue(this, true);
			alSourceStop(streamID);
			alSourcei(streamID, AL_BUFFER, 0);
			audio.freeStream(streamID);
			streamID = -1;
		}
		alDeleteBuffers(buffers);
		buffers = null;
	}
}