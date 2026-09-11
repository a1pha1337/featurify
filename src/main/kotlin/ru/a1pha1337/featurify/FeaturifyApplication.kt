package ru.a1pha1337.featurify

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FeaturifyApplication

fun main(args: Array<String>) {
	runApplication<FeaturifyApplication>(*args)
}
