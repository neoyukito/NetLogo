// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.api

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

import org.nlogo.core.FileMode

import java.io.{ FileOutputStream, File }

object FileIO {

  @throws(classOf[java.io.IOException])
  def file2String(path: String) =
    io.Source.fromFile(path).mkString

  @throws(classOf[java.io.IOException])
  def file2String(file: java.io.File) =
    io.Source.fromFile(file).mkString

  @throws(classOf[java.io.IOException])
  def url2String(sampleURL: String): String = {
    if(sampleURL.startsWith("/"))
      getResourceAsString(sampleURL)
    else {
      val massagedURL =
        if(!System.getProperty("os.name").startsWith("Mac")) {
          val badStart = "file://"
          if(sampleURL.indexOf(badStart) != -1)
            "file:/" + sampleURL.drop(badStart.size)
          else sampleURL
        }
        else sampleURL

      // UTF-8 is needed directly here because it seems that applets can't be
      // passed -D params. So, we can't use -Dfile.encoding=UTF-8 like we normally do.
      // This shouldn't hurt anything.
      reader2String(
        new java.io.InputStreamReader(
          new java.net.URL(massagedURL)
          .openStream(), "UTF-8"))
    }
  }

  def getResourceLines(path: String): Iterator[String] = {
    val in = new java.io.BufferedReader(
      new java.io.InputStreamReader(
        getClass.getResourceAsStream(path)))
    Iterator.continually(in.readLine()).takeWhile(_ != null)
  }

  // for convenience when calling from Java
  def getResourceAsStringArray(path: String): Array[String] =
    getResourceLines(path).toArray

  def getResourceAsString(path: String): String =
    getResourceLines(path).mkString("", "\n", "\n")

  @throws(classOf[java.io.IOException])
  def writeFile(path: String, text: String) {
    writeFile(path, text, false)
  }

  @throws(classOf[java.io.IOException])
  def writeImageFile(image: BufferedImage, filename: String, format: String): Unit = {
    // there's a form of ImageIO.write that just takes a filename, but
    // if we use that when the filename is invalid (e.g. refers to
    // a directory that doesn't exist), we get an IllegalArgumentException
    // instead of an IOException, so we make our own OutputStream
    // so we get the proper exceptions. - ST 8/19/03, 11/26/03
    val stream = new FileOutputStream(new File(filename))
    ImageIO.write(image, format, stream)
    stream.close()
  }

  @throws(classOf[java.io.IOException])
  def reader2String(reader: java.io.Reader): String =
    reader2String(reader, 8192) // arbitrary default

  // separate method with configurable bufferSize for easy testing with ScalaCheck.
  // for now we can't just use a default argument because most of our callers
  // are from Java - ST 12/22/09
  private[nlogo] def reader2String(reader: java.io.Reader, bufferSize: Int = 8192): String = {
    assert(bufferSize > 0)
    val sb = new StringBuilder
    val buffer = Array.fill(bufferSize)('\u0000')
    Iterator.continually(reader.read(buffer))
      .takeWhile(_ != -1)
      .foreach(sb.appendAll(buffer, 0, _))
    reader.close()
    sb.toString
  }

  @throws(classOf[java.io.IOException])
  def writeFile(path: String, text: String, convertToPlatformLineBreaks: Boolean) {
    val file = new LocalFile(path)
    try {
      file.open(FileMode.Write)
      if (!convertToPlatformLineBreaks)
        file.print(text)
      else {
        val lineReader = new java.io.BufferedReader(
          new java.io.StringReader(text))
        val lines =
          Iterator.continually(lineReader.readLine()).takeWhile(_ != null)
        for(line <- lines)
          file.println(line)
      }
      file.close(true)
    }
    finally file.close(false)
  }
}
