# Contributing to MCloud

Thank you for your interest in contributing to MCloud!

MCloud is an open-source project created and maintained by **UFzDev**. To maintain architectural integrity, determinism, and prevent fragmented or unverified forks, all contributions and issue reports must be submitted through the official GitHub repository:

- **Official Repository**: [https://github.com/UFzDev/Mcloud](https://github.com/UFzDev/Mcloud)
- **Official Issue Tracker**: [https://github.com/UFzDev/Mcloud/issues](https://github.com/UFzDev/Mcloud/issues)

---

## Guidelines for Contributions

1. **Bug Reports & Feature Requests**:
   - Please open an issue in the official issue tracker describing the environment (Minecraft version, Fabric Loader version, OS, network setup).
2. **Pull Requests**:
   - All Pull Requests must target the `main` branch of `UFzDev/Mcloud`.
   - Code must adhere to strict Netty and Java 17+ best practices: zero heap allocation in hot paths, vectorized XOR algorithms, and clean Netty pipeline handling.
   - Every change must pass the test suite:
     ```bash
     ./gradlew test
     ```
3. **Attribution & Provenance**:
   - All submitted contributions become part of the MCloud codebase governed under the project's MIT License with UFzDev attribution.
