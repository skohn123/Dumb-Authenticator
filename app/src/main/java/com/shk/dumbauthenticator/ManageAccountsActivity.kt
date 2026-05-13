package com.shk.dumbauthenticator

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class ManageAccountsActivity : ComponentActivity() {

    private lateinit var storageHelper: StorageHelper
    private var accounts = mutableListOf<StorageHelper.Account>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_accounts)

        findViewById<TextView>(R.id.topBarTitle).text = "MANAGE ACCOUNTS"
        findViewById<ImageButton>(R.id.btnTopBack).setOnClickListener { finish() }

        storageHelper = StorageHelper(this)
        val listView = findViewById<ListView>(R.id.listManageAccounts)

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.setTextColor(getColor(R.color.text_primary))
                tv.textSize = 14f
                tv.typeface = android.graphics.Typeface.MONOSPACE
                tv.setPadding(40, 28, 40, 28)
                return v
            }
        }
        listView.adapter = adapter
        reload()

        listView.setOnItemClickListener { _, _, _, _ -> showMultiDeleteDialog() }
        listView.setOnItemLongClickListener { _, _, _, _ ->
            showMultiDeleteDialog()
            true
        }
    }

    private fun reload() {
        accounts.clear()
        accounts.addAll(storageHelper.getAllAccounts())
        adapter.clear()
        adapter.addAll(accounts.map { "  ${it.label}" })
    }

    private fun showMultiDeleteDialog() {
        if (accounts.isEmpty()) {
            Toast.makeText(this, "No accounts to remove", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.map { it.label }.toTypedArray()
        val checked = BooleanArray(accounts.size)

        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Select accounts to delete")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Delete") { _, _ ->
                val toDelete = accounts.filterIndexed { i, _ -> checked[i] }
                if (toDelete.isEmpty()) return@setPositiveButton
                AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
                    .setTitle("Confirm delete")
                    .setMessage("Permanently remove ${toDelete.size} account(s)?\n\nYou will lose access to their TOTP codes.")
                    .setPositiveButton("Delete") { _, _ ->
                        toDelete.forEach { storageHelper.deleteAccount(it.label) }
                        reload()
                        Toast.makeText(this, "Deleted ${toDelete.size}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
