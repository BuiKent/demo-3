package com.example.realtalkenglishwithAI.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.realtalkenglishwithAI.R
import com.example.realtalkenglishwithAI.databinding.ListItemRoleplayScenarioBinding
import com.example.realtalkenglishwithAI.model.RoleplayScenario

class RoleplayScenarioAdapter(private val scenarios: List<RoleplayScenario>) :
    RecyclerView.Adapter<RoleplayScenarioAdapter.ScenarioViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScenarioViewHolder {
        val binding = ListItemRoleplayScenarioBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ScenarioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScenarioViewHolder, position: Int) {
        holder.bind(scenarios[position])
    }

    override fun getItemCount() = scenarios.size

    class ScenarioViewHolder(private val binding: ListItemRoleplayScenarioBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(scenario: RoleplayScenario) {
            binding.scenarioTitle.text = scenario.title
            binding.scenarioImage.load(scenario.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_practice) // Optional placeholder
                error(R.drawable.ic_practice) // Optional error image
            }
        }
    }
}
