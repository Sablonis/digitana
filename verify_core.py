from core.models import User, Circle, Role, RoleAssignment

# Create a Circle
c = Circle.objects.create(name="General Circle", purpose="Top level circle")
print(f"Created Circle: {c}")

# Create a Role
r = Role.objects.create(name="Facilitator", scope=Role.Scope.CIRCLE)
print(f"Created Role: {r}")

# Get User
u = User.objects.get(username="admin")
print(f"Got User: {u}")

# Assign Role
ra = RoleAssignment.objects.create(user=u, role=r, circle=c)
print(f"Created Assignment: {ra}")

# Verify Reverse Relations
print(f"User Assignments: {u.role_assignments.count()}")
print(f"Circle Assignments: {c.role_assignments.count()}")
