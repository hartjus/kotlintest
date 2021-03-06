package io.kotlintest.assertions.arrow.nel

import arrow.data.NonEmptyList
import io.kotlintest.Matcher
import io.kotlintest.Result
import io.kotlintest.should
import io.kotlintest.shouldNot

fun <T> NonEmptyList<T>.shouldContainOnlyNulls() = this should containOnlyNulls()
fun <T> NonEmptyList<T>.shouldNotContainOnlyNulls() = this shouldNot containOnlyNulls()
fun <T> containOnlyNulls() = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) =
      Result(
          value.all.all { it == null },
          "NonEmptyList should contain only nulls",
          "NonEmptyList should not contain only nulls"
      )
}

fun <T> NonEmptyList<T>.shouldContainNull() = this should containNull()
fun <T> NonEmptyList<T>.shouldNotContainNull() = this shouldNot containNull()
fun <T> containNull() = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) =
      Result(
          value.all.any { it == null },
          "NonEmptyList should contain at least one null",
          "NonEmptyList should not contain any nulls"
      )
}

fun <T> NonEmptyList<T>.shouldContainElementAt(index: Int, element: T) = this should haveElementAt(index, element)
fun <T> NonEmptyList<T>.shouldNotContainElementAt(index: Int, element: T) = this shouldNot haveElementAt(index, element)
fun <T> haveElementAt(index: Int, element: T) = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) =
      Result(
          value.all[index] == element,
          "NonEmptyList should contain $element at index $index",
          "NonEmptyList should not contain $element at index $index"
      )
}

fun <T> NonEmptyList<T>.shouldContainNoNulls() = this should containNoNulls()
fun <T> NonEmptyList<T>.shouldNotContainNoNulls() = this shouldNot containNoNulls()
fun <T> containNoNulls() = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) =
      Result(
          value.all.all { it != null },
          "NonEmptyList should not contain nulls",
          "NonEmptyList should have at least one null"
      )
}

fun <T> NonEmptyList<T>.shouldContain(t: T) = this should contain(t)
fun <T> NonEmptyList<T>.shouldNotContain(t: T) = this shouldNot contain(t)
fun <T> contain(t: T) = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) = Result(
      value.all.contains(t),
      "NonEmptyList should contain element $t",
      "NonEmptyList should not contain element $t"
  )
}

fun NonEmptyList<Any>.shouldBeUnique() = this shouldNot haveDuplicates()
fun NonEmptyList<Any>.shouldNotBeUnique() = this should haveDuplicates()

fun NonEmptyList<Any>.shouldHaveDuplicates() = this should haveDuplicates()
fun NonEmptyList<Any>.shouldNotHaveDuplicates() = this shouldNot haveDuplicates()
fun <T> haveDuplicates() = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) = Result(
      value.all.toSet().size < value.size,
      "NonEmptyList should contain duplicates",
      "NonEmptyList should not contain duplicates"
  )
}

fun <T> NonEmptyList<T>.shouldContainAll(vararg ts: T) = this should containAll(*ts)
fun <T> NonEmptyList<T>.shouldNotContainAll(vararg ts: T) = this shouldNot containAll(*ts)
fun <T> NonEmptyList<T>.shouldContainAll(ts: List<T>) = this should containAll(ts)
fun <T> NonEmptyList<T>.shouldNotContainAll(ts: List<T>) = this shouldNot containAll(ts)
fun <T> containAll(vararg ts: T) = containAll(ts.asList())
fun <T> containAll(ts: List<T>): Matcher<NonEmptyList<T>> = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) = Result(
      ts.all { value.contains(it) },
      "NonEmptyList should contain all of ${ts.take(10).joinToString(",")}",
      "NonEmptyList should not contain all of ${ts.take(10).joinToString(",")}"
  )
}

fun NonEmptyList<Any>.shouldHaveSize(size: Int) = this should haveSize(size)
fun NonEmptyList<Any>.shouldNotHaveSize(size: Int) = this shouldNot haveSize(size)
fun <T> haveSize(size: Int): Matcher<NonEmptyList<T>> = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) =
      Result(
          value.size == size,
          "NonEmptyList should have size $size but has size ${value.size}",
          "NonEmptyList should not have size $size"
      )
}

fun <T> NonEmptyList<T>.shouldBeSingleElement(t: T) = this should singleElement(t)
fun <T> NonEmptyList<T>.shouldNotBeSingleElement(t: T) = this shouldNot singleElement(t)
fun <T> singleElement(t: T): Matcher<NonEmptyList<T>> = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>) = Result(
      value.size == 1 && value.head == t,
      "NonEmptyList should be a single element of $t but has ${value.size} elements",
      "NonEmptyList should not be a single element of $t"
  )
}

fun <T : Comparable<T>> NonEmptyList<T>.shouldBeSorted() = this should beSorted<T>()
fun <T : Comparable<T>> NonEmptyList<T>.shouldNotBeSorted() = this shouldNot beSorted<T>()
fun <T : Comparable<T>> beSorted(): Matcher<NonEmptyList<T>> = object : Matcher<NonEmptyList<T>> {
  override fun test(value: NonEmptyList<T>): Result {
    val passed = value.all.sorted() == value.all
    val snippet = if (value.size <= 10) value.all.joinToString(",") else value.all.take(10).joinToString(",") + "..."
    return Result(
        passed,
        "NonEmptyList $snippet should be sorted",
        "NonEmptyList $snippet should not be sorted"
    )
  }
}