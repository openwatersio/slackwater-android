"""Check that Studio can verify the sources for the current Gradle distribution."""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text()
distribution = re.search(r"^distributionUrl=.*?/gradle-([^/]+)-(?:bin|all)\.zip$", wrapper, re.M)
if not distribution:
    sys.exit("Cannot identify the Gradle version from the wrapper distribution URL.")

version = distribution[1]
archive = f"gradle-{version}-src.zip"
component = f".//{{*}}component[@group='gradle'][@name='gradle'][@version='{version}']"
metadata = ET.parse("gradle/verification-metadata.xml")
pin = metadata.find(f"{component}/{{*}}artifact[@name='{archive}']/{{*}}sha256")
if pin is None or not re.fullmatch(r"[0-9a-fA-F]{64}", pin.get("value", "")):
    sys.exit(f"Add the publisher's SHA-256 for {archive} to the verification metadata.")
