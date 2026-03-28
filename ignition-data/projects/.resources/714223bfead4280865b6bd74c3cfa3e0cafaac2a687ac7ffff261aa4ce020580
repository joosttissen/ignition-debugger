def doGet(request, session):
	def build_greeting(name):
		prefix = "Hello"
		separator = ", "
		suffix = "!"
		greeting = "Hello, " + name + "!"
		return greeting

	name = "Debugger"
	message = build_greeting(name)
	lib_message = gateway_scripts.greet(name)
	result = {"greeting": message, "name": name, "source": "webdev", "lib": lib_message}
	return {"json": result}
