function saveConfig(event) {
  event.preventDefault();
  
  const slugInput = document.getElementById('companySlug');
  const errorMsg = document.getElementById('errorMsg');
  const rawSlug = slugInput.value.trim();
  
  // Basic validation: only letters, numbers, hyphens, and underscores
  const slugRegex = /^[a-zA-Z0-9-_]+$/;
  
  if (!rawSlug || !slugRegex.test(rawSlug)) {
    // Show error message
    errorMsg.style.display = 'block';
    slugInput.classList.add('error-active');
    
    // Animate error message shake
    errorMsg.style.animation = 'none';
    errorMsg.offsetHeight; /* trigger reflow */
    errorMsg.style.animation = null;
    return;
  }
  
  errorMsg.style.display = 'none';
  
  // Navigate to custom scheme to let native Android side save it and redirect
  window.location.href = 'setcompany://' + rawSlug;
}

// Clean error on typing
document.getElementById('companySlug').addEventListener('input', function() {
  document.getElementById('errorMsg').style.display = 'none';
});
