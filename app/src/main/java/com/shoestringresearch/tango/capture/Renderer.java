package com.shoestringresearch.tango.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.util.Log;

import com.google.atap.tangoservice.TangoCameraIntrinsics;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES30.*;

class Renderer implements GLSurfaceView.Renderer {
   // This constant is not yet defined in android.opengl.GLES20.
   static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

   static final String videoVertexSource =
      "attribute vec4 a_v;\n" +
      "varying vec2 t;\n" +
      "void main() {\n" +
      "	gl_Position = a_v;\n" +
      "	t = 0.5*vec2(a_v.x, -a_v.y) + vec2(0.5,0.5);\n" +
      "}\n";

   static final String videoFragmentSource =
      "#extension GL_OES_EGL_image_external : require\n" +
      "precision mediump float;\n" +
      "varying vec2 t;\n" +
      "uniform samplerExternalOES colorTex;\n" +
      "void main() {\n" +
      "	gl_FragColor = texture2D(colorTex, t);\n" +
      "}\n";

   MainActivity activity_;

   int videoProgram_;
   int videoVertexAttribute_;
   int videoVertexBuffer_;

   int videoTextureName_;
   SurfaceTexture surfaceTexture_;

   int offscreenBuffer_;
   int offscreenWidth_;
   int offscreenHeight_;

   volatile boolean saveNextFrame_;

   Renderer(MainActivity activity) {
      activity_ = activity;
   }

   @Override
   public void onSurfaceCreated(GL10 gl, EGLConfig config) {
      Log.d("RTH", "onSurfaceCreated");
      glClearColor(0.3f, 0.3f, 0.3f, 1.0f);

      FloatBuffer vBuffer = ByteBuffer.allocateDirect(2*4*4)
              .order(ByteOrder.nativeOrder())
              .asFloatBuffer();
      vBuffer.put(-1.0f);  vBuffer.put(1.0f);
      vBuffer.put(-1.0f);  vBuffer.put(-1.0f);
      vBuffer.put(1.0f);   vBuffer.put(1.0f);
      vBuffer.put(1.0f);   vBuffer.put(-1.0f);

      IntBuffer bufferNames = IntBuffer.allocate(1);
      glGenBuffers(2, bufferNames);
      videoVertexBuffer_ = bufferNames.get(0);

      glBindBuffer(GL_ARRAY_BUFFER, videoVertexBuffer_);
      glBufferData(GL_ARRAY_BUFFER, vBuffer.capacity() * 4, vBuffer.position(0), GL_STATIC_DRAW);

      // Create the video texture.
      IntBuffer textureNames = IntBuffer.allocate(1);
      glGenTextures(1, textureNames);
      videoTextureName_ = textureNames.get(0);

      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureName_);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

      // Connect the texture to Tango.
      surfaceTexture_ = new SurfaceTexture(videoTextureName_);
      activity_.runOnUiThread(new Runnable() {
         public void run() {
            activity_.attachTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR, videoTextureName_);
         }
      });

      // Prepare the video shader.
      videoProgram_ = createShaderProgram(videoVertexSource, videoFragmentSource);
      glUseProgram(videoProgram_);
      videoVertexAttribute_ = glGetAttribLocation(videoProgram_, "a_v");
      glUniform1i(
              glGetUniformLocation(videoProgram_, "colorTex"),
              0);  // GL_TEXTURE0

      TangoCameraIntrinsics intrinsics = activity_.getTango().getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
      offscreenWidth_ = intrinsics.width;
      offscreenHeight_  = intrinsics.height;

      IntBuffer renderbufferName = IntBuffer.allocate(1);
      glGenRenderbuffers(1, renderbufferName);
      glBindRenderbuffer(GL_RENDERBUFFER, renderbufferName.get(0));
      glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, offscreenWidth_, offscreenHeight_);

      IntBuffer framebufferName = IntBuffer.allocate(1);
      glGenFramebuffers(1, framebufferName);
      glBindFramebuffer(GL_FRAMEBUFFER, framebufferName.get(0));
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbufferName.get(0));

      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
         throw new RuntimeException("framebuffer incomplete");

      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      offscreenBuffer_ = framebufferName.get(0);
   }

   @Override
   public void onSurfaceChanged(GL10 gl, int width, int height) {
   }

   @Override
   public void onDrawFrame(GL10 gl) {
      activity_.updateTexture(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);

      if (!saveNextFrame_) {
         glBindFramebuffer(GL_FRAMEBUFFER, 0);
         glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);

         glUseProgram(videoProgram_);
         glBindBuffer(GL_ARRAY_BUFFER, videoVertexBuffer_);
         glVertexAttribPointer(videoVertexAttribute_, 2, GL_FLOAT, false, 0, 0);
         glEnableVertexAttribArray(videoVertexAttribute_);
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureName_);
         glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
      }
      else {
         glBindFramebuffer(GL_FRAMEBUFFER, offscreenBuffer_);
         glClear(GL_COLOR_BUFFER_BIT);

         glUseProgram(videoProgram_);
         glBindBuffer(GL_ARRAY_BUFFER, videoVertexBuffer_);
         glVertexAttribPointer(videoVertexAttribute_, 2, GL_FLOAT, false, 0, 0);
         glEnableVertexAttribArray(videoVertexAttribute_);
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureName_);
         glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

         IntBuffer intBuffer = ByteBuffer.allocateDirect(offscreenWidth_ * offscreenHeight_ * 4)
                 .order(ByteOrder.nativeOrder())
                 .asIntBuffer();
         glReadPixels(0, 0, offscreenWidth_, offscreenHeight_, GL_RGBA, GL_UNSIGNED_BYTE, intBuffer.rewind());

         int[] pixels = new int[intBuffer.capacity()];
         intBuffer.rewind();
         intBuffer.get(pixels);

         try {
            File directory = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Tango Captures");
            directory.mkdirs();

            SharedPreferences prefs = activity_.getPreferences(Context.MODE_PRIVATE);
            int index = prefs.getInt("index", 0);

            File file = new File(directory, String.format("tango%05d.png", index));
            FileOutputStream fileOutputStream = new FileOutputStream(file);

            Bitmap bitmap = Bitmap.createBitmap(pixels, offscreenWidth_, offscreenHeight_, Bitmap.Config.ARGB_8888);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fileOutputStream);
            fileOutputStream.close();
         }
         catch (Exception e) {
            e.printStackTrace();
         }

         saveNextFrame_ = false;
      }
   }

   void saveFrame() {
      saveNextFrame_ = true;
   }

   private int createShaderProgram(String vertexSource, String fragmentSource) {
      int vsName = glCreateShader(GL_VERTEX_SHADER);
      glShaderSource(vsName, vertexSource);
      glCompileShader(vsName);
      System.out.println(glGetShaderInfoLog(vsName));

      int fsName = glCreateShader(GL_FRAGMENT_SHADER);
      glShaderSource(fsName, fragmentSource);
      glCompileShader(fsName);
      System.out.println(glGetShaderInfoLog(fsName));

      int programName = glCreateProgram();
      glAttachShader(programName, vsName);
      glAttachShader(programName, fsName);
      glLinkProgram(programName);
      System.out.println(glGetProgramInfoLog(programName));

      return programName;
   }

}