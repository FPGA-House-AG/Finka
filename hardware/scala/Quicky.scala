import scala.collection.mutable.ListBuffer

// sbt "~runMain Quicky" to quickly test some Scala and/or SpinalHDL code

object Quicky {
  def main(args: Array[String]) {
    // Split literal packet bytes, specified as a string, across BigInt words of specific dataWidth
    val dataWidth = 128
    val payload =
      "01 02 03 04 05 06 01 02 03 04 05 06 01 02 45 11 22 33 44 55 66 77 88 11 00 00 00 00 00 00 00 00 00 00 15 b3 15 b3 01 72 00 00 04 00 00 00 11 22 33 44 c1 c2 c3 c4 c5 c6 c7 c8 4c 61 64 69 65 73 " +
      "20 61 6e 64 20 47 65 6e 74 6c 65 6d 65 6e 20 6f 66 20 74 68 65 20 63 6c 61 73 73 20 6f 66 20 27 39 39 3a 20 49 66 20 49 20 63 6f 75 6c 64 20 6f 66 66 65 72 20 79 6f 75 20 6f 6e 6c 79 20 6f 6e " +
      "65 20 74 69 70 20 66 6f 72 20 74 68 65 20 66 75 74 75 72 65 2c 20 73 75 6e 73 63 72 65 65 6e 20 77 6f 75 6c 64 20 62 65 20 69 74 2e 13 05 13 05 13 05 13 05 13 05 13 05 13 05 13 05 00 00 00 00 "

    /* create a List of Arrays */
    val words = payload.split(" ").grouped(dataWidth/8)
    var strs  = new ListBuffer[BigInt]()

    words.zipWithIndex.foreach {
      case (word, count) => {
        //printf("%s\n", word/*.reverse.*/.mkString(""))
        //printf("%s\n", word.reverse.mkString(""))
        strs += BigInt(word.reverse.mkString(""), 16)
      }
    }
  }
}

// Split literal packet bytes, specified as a string, across BigInt words of specific dataWidth
object Quicky2 {
  def main(args: Array[String]) {
    val dataWidth = 128
    val payload =
      // @TODO check how we can split across the 0809 boundary, where a space is missing
      "01 02 03 04 05 06 07 08" +
      "09 0a 0b 0c 0d 0e 0f 10"

    /* create a List of Arrays */
    val words = payload.split(" ").grouped(dataWidth/8)
    var strs  = new ListBuffer[BigInt]()

    words.zipWithIndex.foreach {
      case (word, count) => {
        //printf("%s\n", word/*.reverse.*/.mkString(""))
        //printf("%s\n", word.reverse.mkString(""))
        strs += BigInt(word.reverse.mkString(""), 16)
      }
    }
    val list_of_bigint_words = strs.toList
  }
}