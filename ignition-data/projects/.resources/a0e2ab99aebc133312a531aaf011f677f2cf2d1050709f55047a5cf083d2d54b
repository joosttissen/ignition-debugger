def doGet(request, session):
	import sys
	import __builtin__
	_trace = getattr(__builtin__, '__debugger_trace__', None)
	if _trace:
		sys.settrace(_trace)

	def build_greeting(name):
		prefix = "Hello"
		separator = ", "
		suffix = "!"
		greeting = "Hello, " + name + "!"
		return greeting

	name = "Debugger"
	message = build_greeting(name)
	result = {"greeting": message, "name": name, "source": "webdev"}
	return {"json": result}
