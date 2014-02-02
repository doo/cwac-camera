/***
  Copyright (c) 2014 CommonsWare, LLC
  
  Licensed under the Apache License, Version 2.0 (the "License"); you may
  not use this file except in compliance with the License. You may obtain
  a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.commonsware.cwac.camera;

public class PictureTransaction {
  CameraHost host=null;
  boolean needBitmap=false;
  boolean needByteArray=true;
  private Object tag=null;
  boolean mirrorFFC=false;
  boolean rotateBasedOnExif=false;
  boolean useSingleShotMode=false;

  public PictureTransaction(CameraHost host) {
    this.host=host;
  }

  public PictureTransaction needBitmap(boolean needBitmap) {
    this.needBitmap=needBitmap;

    return(this);
  }

  public PictureTransaction needByteArray(boolean needByteArray) {
    this.needByteArray=needByteArray;

    return(this);
  }

  public Object getTag() {
    return(tag);
  }

  public PictureTransaction tag(Object tag) {
    this.tag=tag;

    return(this);
  }

  boolean useSingleShotMode() {
    return(useSingleShotMode || host.useSingleShotMode());
  }

  boolean mirrorFFC() {
    return(mirrorFFC || host.mirrorFFC());
  }

  boolean rotateBasedOnExif() {
    return(rotateBasedOnExif || host.rotateBasedOnExif());
  }

  public PictureTransaction useSingleShotMode(boolean useSingleShotMode) {
    this.useSingleShotMode=useSingleShotMode;

    return(this);
  }

  public PictureTransaction mirrorFFC(boolean mirrorFFC) {
    this.mirrorFFC=mirrorFFC;

    return(this);
  }

  public PictureTransaction rotateBasedOnExif(boolean rotateBasedOnExif) {
    this.rotateBasedOnExif=rotateBasedOnExif;

    return(this);
  }
}
