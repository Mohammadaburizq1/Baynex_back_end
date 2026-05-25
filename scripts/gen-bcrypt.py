import bcrypt
import sys
password = sys.argv[1] if len(sys.argv) > 1 else "ShopLinkAdmin1!"
print(bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=12)).decode())
