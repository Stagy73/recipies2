package com.ai.recipies

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import coil.load
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Properties

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerPeople: Spinner
    private lateinit var spinnerCuisine: Spinner
    private lateinit var spinnerCategory: Spinner
    private lateinit var spinnerSubCategory: Spinner
    private lateinit var btnGenerate: Button
    private lateinit var tvRecipe: TextView
    private lateinit var ivRecipeImage: ImageView
    private lateinit var btnShare: Button

    private var imageUrl: String = ""
    private var currentRecipe: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        spinnerPeople = findViewById(R.id.spinnerPeople)
        spinnerCuisine = findViewById(R.id.spinnerCuisine)
        spinnerCategory = findViewById(R.id.spinnerCategory)
        spinnerSubCategory = findViewById(R.id.spinnerSubCategory)
        btnGenerate = findViewById(R.id.btnGenerate)
        tvRecipe = findViewById(R.id.tvRecipe)
        ivRecipeImage = findViewById(R.id.ivRecipeImage)
        btnShare = findViewById(R.id.btnShare)

        // Setup spinners
        setupSpinner(R.array.people_numbers, spinnerPeople)
        setupSpinner(R.array.cuisine_types, spinnerCuisine)
        setupSpinner(R.array.main_categories, spinnerCategory)

        // Update sub-category spinner based on main category selection
        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val selectedCategory = parent?.getItemAtPosition(position).toString()
                val subCategoryArrayId = when (selectedCategory) {
                    "Viande" -> R.array.meat_types
                    "Poisson" -> R.array.fish_types
                    "Végétarien" -> 0
                    else -> 0
                }
                if (subCategoryArrayId != 0) {
                    spinnerSubCategory.visibility = View.VISIBLE
                    setupSpinner(subCategoryArrayId, spinnerSubCategory)
                } else {
                    spinnerSubCategory.visibility = View.GONE
                    spinnerSubCategory.adapter = null
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                spinnerSubCategory.visibility = View.GONE
                spinnerSubCategory.adapter = null
            }
        }

        btnGenerate.setOnClickListener {
            val people = spinnerPeople.selectedItem.toString()
            val cuisine = spinnerCuisine.selectedItem.toString()
            val category = spinnerCategory.selectedItem.toString()
            val subCategory = if (spinnerSubCategory.adapter != null && spinnerSubCategory.selectedItem != null)
                spinnerSubCategory.selectedItem.toString() else ""

            generateRecipe(people, cuisine, category, subCategory)
        }

        btnShare.setOnClickListener {
            if (currentRecipe.isNotEmpty()) {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "$currentRecipe\n\nImage: $imageUrl")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(shareIntent, "Partager la recette via"))
            }
        }
    }

    private fun setupSpinner(arrayId: Int, spinner: Spinner) {
        ArrayAdapter.createFromResource(
            this, arrayId, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }
    }

    private fun generateRecipe(people: String, cuisine: String, category: String, subCategory: String) {
        val props = assets.open("local.properties").bufferedReader().use { reader ->
            val properties = Properties()
            properties.load(reader)
            properties
        }
        val apiKey = props.getProperty("OPENAI_API_KEY")?.trim()
            ?: throw IllegalArgumentException("API key not found in local.properties")

        val client = OkHttpClient()

        val prompt = """
            Génère une recette détaillée avec:
            - Titre: [Nom de la recette]
            - Portions: $people
            - Type: $cuisine
            - Catégorie: $category ${if (subCategory.isNotEmpty()) "($subCategory)" else ""}
            - Ingrédients: [liste avec quantités]
            - Étapes: [numérotées]
            - Astuce: [optionnelle]
            Formatte en Markdown.
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        runOnUiThread {
            btnGenerate.isEnabled = false
            tvRecipe.text = "Recherche de recette en cours..."
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvRecipe.text = "Erreur de connexion: ${e.message}"
                    btnGenerate.isEnabled = true
                }
                Log.e("API_ERROR", "Network error", e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""
                    Log.d("API_DEBUG", "Response: $body")

                    if (!response.isSuccessful) {
                        runOnUiThread {
                            tvRecipe.text = "Erreur API (${response.code}): $body"
                            btnGenerate.isEnabled = true
                        }
                        return
                    }

                    val jsonResponse = JSONObject(body)
                    val recipe = jsonResponse.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "Aucune recette trouvée")
                        ?: "Format de réponse inattendu"

                    imageUrl = "https://source.unsplash.com/600x400/?${URLEncoder.encode("$cuisine $category $subCategory", "UTF-8")}"

                    runOnUiThread {
                        currentRecipe = recipe
                        tvRecipe.text = recipe
                        ivRecipeImage.load(imageUrl) {
                            crossfade(true)
                        }
                        btnGenerate.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvRecipe.text = "Erreur de traitement: ${e.localizedMessage}"
                        btnGenerate.isEnabled = true
                    }
                    Log.e("API_ERROR", "Processing error", e)
                }
            }
        })
    }
}
