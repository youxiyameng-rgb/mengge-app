package com.aivoice.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.aivoice.app.R
import com.aivoice.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardDubbing.setOnClickListener { findNavController().navigate(R.id.action_home_to_dubbing) }
        binding.cardCover.setOnClickListener { findNavController().navigate(R.id.action_home_to_covers) }
        binding.cardClone.setOnClickListener { findNavController().navigate(R.id.action_home_to_clone) }
        binding.cardSeparator.setOnClickListener { findNavController().navigate(R.id.action_home_to_separator) }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
